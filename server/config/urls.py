from django.urls import path

from chat.views import MessagesView

urlpatterns = [
    path("messages", MessagesView.as_view()),
]
