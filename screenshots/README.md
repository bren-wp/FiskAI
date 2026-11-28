# Stvarne snimke FiskAI

CI Android device and screenshots pokreće instalirani APK na emulatoru API 36, izvršava instrumentacijske testove i snima stvarne zaslone. Artefakt FiskAI-real-screenshots-api36 sadrži PNG datoteke nakon uspješnog izvođenja. Snimke nisu ručno simulirani dizajni. Originalne reference iz brending/ ostaju odvojene. PNG datoteke se ne objavljuju kao marketinške snimke dok se ne pregledaju i ne usporede s odobrenim dizajnom. Pixel-perfect verifikacija još nije završena.

Web and administration: `scripts/capture-web-admin.py` creates actual browser PNGs in `screenshots/actual/web` and `screenshots/actual/admin` using only the disposable CI database. No generated mockups or real customer data are used. Release assembly consumes fresh CI artifacts. Manual visual reference comparison remains required.
