import sqlite3
import uuid
from unittest import mock

from django.db import connection
from rest_framework.response import Response
from rest_framework.test import APIClient
from django.test import TransactionTestCase


class S5ReplyAfterCommitTests(TransactionTestCase):
    """Real commits (TransactionTestCase) + a second, independent SQLite connection.

    The second connection sees only committed data, so if it can read the row at the moment the
    view builds its Response, the commit happened before the ack. Needs a file-backed test DB
    (settings DATABASES TEST NAME); an in-memory DB is not reachable from another connection.
    """

    def test_S5_row_is_committed_before_the_201_reply_is_built(self):
        msg_id = uuid.uuid4()
        seen_from_outside = []

        def response_spy(data=None, status=None, **kwargs):
            outside = sqlite3.connect(connection.settings_dict["NAME"], timeout=0)
            try:
                row = outside.execute(
                    "SELECT server_seq FROM chat_message WHERE client_msg_id = ?", [msg_id.hex]
                ).fetchone()
            except sqlite3.OperationalError as exc:  # writer still holds the lock: not committed
                row = f"locked: {exc}"
            finally:
                outside.close()
            seen_from_outside.append(row)
            return Response(data, status=status, **kwargs)

        with mock.patch("chat.views.Response", side_effect=response_spy):
            resp = APIClient().post(
                "/messages",
                {"client_msg_id": str(msg_id), "chat_id": "chat-1", "body": "hi"},
                format="json",
                HTTP_X_DEVICE_ID="phone-a",
            )

        self.assertEqual(resp.status_code, 201)
        self.assertEqual(seen_from_outside, [(1,)])
