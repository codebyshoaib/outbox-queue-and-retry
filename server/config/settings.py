"""Settings for the outbox playground server. Dev-only: no auth, DEBUG on, LAN-reachable."""
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = "dev-only-outbox-playground"  # local playground, never deployed
DEBUG = True
ALLOWED_HOSTS = ["*"]  # a phone on the LAN (or the chaos proxy) must reach runserver 0.0.0.0:8080

INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "rest_framework",
    "chat",
]

MIDDLEWARE = ["django.middleware.common.CommonMiddleware"]

ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
APPEND_SLASH = False  # the API is /messages, no trailing slash
USE_TZ = True
TIME_ZONE = "UTC"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [],
    "DEFAULT_PERMISSION_CLASSES": [],
    "UNAUTHENTICATED_USER": None,
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
}
