rootProject.name = "ttokttok-backend"

include(
    "domain",
    "application",
    "adapter-in-web",
    "adapter-in-scheduler",
    "adapter-out-persistence",
    "adapter-out-notification",
    "adapter-out-realtime",
    "bootstrap:app-api",
    "bootstrap:app-worker",
)
