from django.db import models


class Chat(models.Model):
    chat_id = models.CharField(max_length=255, unique=True)
    last_seq = models.PositiveIntegerField(default=0)


class Message(models.Model):
    chat = models.ForeignKey(Chat, on_delete=models.CASCADE, related_name="messages")
    sender_device_id = models.CharField(max_length=255)
    client_msg_id = models.UUIDField()
    body = models.TextField()
    server_seq = models.PositiveIntegerField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            # The idempotency key: a retry of the same send can never become a second row.
            models.UniqueConstraint(fields=["sender_device_id", "client_msg_id"], name="uniq_device_client_msg"),
            # Backstop for seq allocation: two messages can never share a seq in one chat.
            models.UniqueConstraint(fields=["chat", "server_seq"], name="uniq_chat_seq"),
        ]
