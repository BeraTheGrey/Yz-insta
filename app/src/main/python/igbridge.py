"""Bridge between the Android app and Instaloader."""

import instaloader


def version():
    return instaloader.__version__
