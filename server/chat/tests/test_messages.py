import uuid
from unittest import mock

from django.apps import apps
from django.db import IntegrityError
from rest_framework.test import APITestCase

DEVICE = "phone-a"


def stored():
    # Looked up lazily so a missing model shows up as a failing assertion, not an import error.
    return apps.get_model("chat", "Message").objects


class MessagesTestCase(APITestCase):
    def post(self, chat_id="chat-1", body="hello", client_msg_id=None, device=DEVICE):
        payload = {
            "client_msg_id": str(client_msg_id or uuid.uuid4()),
            "chat_id": chat_id,
            "body": body,
        }
        headers = {} if device is None else {"HTTP_X_DEVICE_ID": device}
        return self.client.post("/messages", payload, format="json", **headers)


class S1CreateTests(MessagesTestCase):
    def test_S1_new_message_returns_201_with_seq_and_is_stored(self):
        msg_id = uuid.uuid4()

        resp = self.post(chat_id="chat-1", body="hello", client_msg_id=msg_id)

        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.json(), {"client_msg_id": str(msg_id), "server_seq": 1})
        row = stored().get()
        self.assertEqual(
            (row.chat.chat_id, row.sender_device_id, row.client_msg_id, row.body, row.server_seq),
            ("chat-1", DEVICE, msg_id, "hello", 1),
        )


class S2IdempotencyTests(MessagesTestCase):
    def test_S2_duplicate_returns_same_seq(self):
        msg_id = uuid.uuid4()
        first = self.post(client_msg_id=msg_id)

        again = self.post(client_msg_id=msg_id)

        self.assertEqual(again.status_code, 200)
        self.assertEqual(again.json(), {"client_msg_id": str(msg_id), "server_seq": first.json()["server_seq"]})
        self.assertEqual(stored().count(), 1)

    def test_S2_same_client_msg_id_from_another_device_is_a_new_message(self):
        msg_id = uuid.uuid4()
        self.post(client_msg_id=msg_id, device="phone-a")

        other = self.post(client_msg_id=msg_id, device="phone-b")

        self.assertEqual(other.status_code, 201)
        self.assertEqual(other.json()["server_seq"], 2)
        self.assertEqual(stored().count(), 2)

    def test_S2_duplicate_that_loses_the_insert_race_returns_same_seq(self):
        # Deterministic stand-in for two concurrent requests with the same id: the second request's
        # existence check misses (the first had not committed yet), so it goes on to insert and hits
        # the unique constraint. It must answer 200 with the winner's seq, not 500 and not a new row.
        msg_id = uuid.uuid4()
        winner = self.post(client_msg_id=msg_id)

        calls = []

        def lookup_that_misses_once(device_id, client_msg_id):
            calls.append(client_msg_id)
            if len(calls) == 1:
                return None
            return stored().filter(sender_device_id=device_id, client_msg_id=client_msg_id).first()

        with mock.patch("chat.views.find_existing", side_effect=lookup_that_misses_once, create=True):
            loser = self.post(client_msg_id=msg_id)

        self.assertEqual(loser.status_code, 200)
        self.assertEqual(loser.json(), {"client_msg_id": str(msg_id), "server_seq": winner.json()["server_seq"]})
        self.assertEqual(stored().count(), 1)
        self.assertEqual(len(calls), 2, "expected a missed lookup, then a re-read after the IntegrityError")


class S3SequenceTests(MessagesTestCase):
    def seqs(self, chat_id):
        return list(stored().filter(chat__chat_id=chat_id).order_by("server_seq").values_list("body", "server_seq"))

    def test_S3_seq_is_per_chat_and_starts_at_1(self):
        self.post(chat_id="chat-a", body="a1")
        self.post(chat_id="chat-b", body="b1")
        self.post(chat_id="chat-a", body="a2")

        self.assertEqual(self.seqs("chat-a"), [("a1", 1), ("a2", 2)])
        self.assertEqual(self.seqs("chat-b"), [("b1", 1)])

    def test_S3_seq_follows_arrival_order(self):
        replies = [self.post(body=f"m{i}").json()["server_seq"] for i in range(1, 6)]

        self.assertEqual(replies, [1, 2, 3, 4, 5])
        self.assertEqual(self.seqs("chat-1"), [(f"m{i}", i) for i in range(1, 6)])

    def test_S3_no_gap_after_duplicates_and_a_lost_race(self):
        msg_id = uuid.uuid4()
        self.post(client_msg_id=msg_id, body="first")
        self.post(client_msg_id=msg_id, body="first")
        with mock.patch("chat.views.find_existing", side_effect=[None, stored().get()]):
            self.post(client_msg_id=msg_id, body="first")

        nxt = self.post(body="second")

        self.assertEqual(nxt.json()["server_seq"], 2)
        self.assertEqual(self.seqs("chat-1"), [("first", 1), ("second", 2)])

    def test_S3_db_refuses_two_messages_with_the_same_seq_in_a_chat(self):
        # Backstop for concurrency on a real database: if seq allocation ever races, the DB must
        # reject the duplicate seq rather than silently store it.
        chat = apps.get_model("chat", "Chat").objects.create(chat_id="chat-1", last_seq=1)
        stored().create(chat=chat, sender_device_id="a", client_msg_id=uuid.uuid4(), body="x", server_seq=1)

        with self.assertRaises(IntegrityError):
            stored().create(chat=chat, sender_device_id="b", client_msg_id=uuid.uuid4(), body="y", server_seq=1)
