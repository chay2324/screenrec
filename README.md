# Screen Rec

Small open-source Android screen recorder (MIT). Records the screen plus internal game/app audio
(Android 10+), using hardware encoders. Saves to Movies/ScreenRecs.

Privacy / attack surface: no INTERNET, Bluetooth, USB or storage permissions; the recording service is not
exported (other apps cannot start it); backups disabled; build is not debuggable. Only permissions:
microphone-class permission for internal audio capture, foreground service, notifications.

Build: push to GitHub; the Actions workflow builds ScreenRec.apk and attaches it to a Release.
Limits: apps/games that opt out of audio capture will record silent audio; calls cannot be captured.
