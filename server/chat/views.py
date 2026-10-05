from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Chat, Message


class MessagesView(APIView):
    def post(self, request):
        data = request.data
        chat, _ = Chat.objects.get_or_create(chat_id=data["chat_id"])
        chat.last_seq += 1
        chat.save(update_fields=["last_seq"])
        msg = Message.objects.create(
            chat=chat,
            sender_device_id=request.headers["X-Device-Id"],
            client_msg_id=data["client_msg_id"],
            body=data["body"],
            server_seq=chat.last_seq,
        )
        return Response({"client_msg_id": str(msg.client_msg_id), "server_seq": msg.server_seq}, status=201)
