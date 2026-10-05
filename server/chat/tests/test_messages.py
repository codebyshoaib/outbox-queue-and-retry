import uuid

from django.apps import apps
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
