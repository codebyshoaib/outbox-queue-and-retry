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
