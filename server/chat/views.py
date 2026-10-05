from django.db import IntegrityError, transaction
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Chat, Message
from .serializers import MessageIn


def find_existing(device_id, client_msg_id):
    return Message.objects.filter(sender_device_id=device_id, client_msg_id=client_msg_id).first()


class MessagesView(APIView):
    def post(self, request):
        incoming = MessageIn(data=request.data, context={"device_id": request.headers.get("X-Device-Id")})
        incoming.is_valid(raise_exception=True)
        data = incoming.validated_data
        device_id = data["sender_device_id"]
        msg = find_existing(device_id, data["client_msg_id"])
        status = 200
        if msg is None:
            try:
                # Savepoint: if the insert loses a race, the seq increment rolls back with it.
                with transaction.atomic():
                    Chat.objects.get_or_create(chat_id=data["chat_id"])
                    # Row lock so concurrent sends to one chat take seqs one at a time. No-op on
                    # SQLite (it serialises writers anyway); real on Postgres.
                    chat = Chat.objects.select_for_update().get(chat_id=data["chat_id"])
                    chat.last_seq += 1
                    chat.save(update_fields=["last_seq"])
                    msg = Message.objects.create(
                        chat=chat,
                        sender_device_id=device_id,
                        client_msg_id=data["client_msg_id"],
                        body=data["body"],
                        server_seq=chat.last_seq,
                    )
                status = 201
            except IntegrityError:
                # A concurrent request with the same (device, client_msg_id) committed first.
                msg = find_existing(device_id, data["client_msg_id"])
                if msg is None:  # some other constraint (e.g. uniq_chat_seq) fired: a real error
                    raise
        # S5: built only after the atomic block has committed, so an ack always means "stored".
        # Never wrap this view in a transaction (ATOMIC_REQUESTS / @atomic): the ack would precede the commit.
        return Response({"client_msg_id": str(msg.client_msg_id), "server_seq": msg.server_seq}, status=status)
