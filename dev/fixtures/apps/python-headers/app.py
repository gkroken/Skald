# T9's identity fixture: a Shiny for Python app that also answers GET /headers with the
# request headers it was given, so the probe can check what reaches a published app -- the
# authenticated identity, and never an identity header a client forged.
from shiny import App, render, ui
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route

app_ui = ui.page_fluid(ui.output_text("hello"))


def server(input, output, session):
    @render.text
    def hello():
        return "skald python-headers fixture"


async def headers(request):
    return JSONResponse({k: v for k, v in request.headers.items()})


app = Starlette(routes=[Route("/headers", headers), Mount("/", app=App(app_ui, server))])
