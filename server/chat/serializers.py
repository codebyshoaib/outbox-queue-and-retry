from rest_framework import serializers

from .models import Message


class MessageIn(serializers.Serializer):
    client_msg_id = serializers.UUIDField()
    chat_id = serializers.CharField(max_length=255)
    # Stored verbatim (no trimming), but whitespace-only counts as blank.
    body = serializers.CharField(max_length=4096, trim_whitespace=False)

    def validate_body(self, value):
        if not value.strip():
            raise serializers.ValidationError("This field may not be blank.")
        return value

    def validate(self, attrs):
        device_id = (self.context.get("device_id") or "").strip()
        if not device_id or len(device_id) > 255:
            raise serializers.ValidationError({"X-Device-Id": "Required header, 1 to 255 characters."})
        attrs["sender_device_id"] = device_id
        return attrs


class MessageOut(serializers.ModelSerializer):
    chat_id = serializers.CharField(source="chat.chat_id")

    class Meta:
        model = Message
        fields = ["server_seq", "client_msg_id", "chat_id", "sender_device_id", "body", "created_at"]
