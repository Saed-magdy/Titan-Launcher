# Security

Titan Cockpit Launcher runs on the car's head unit with system permissions and can send
commands to the car (trunk, windows, sunroof, lights). A security problem in it can matter
more than in an ordinary app, so please report it **privately**, not in a public issue.

## How to report a problem

- **Preferred:** on GitHub, open the **Security** tab of this repository and click
  **Report a vulnerability**. Only the maintainer can see the report.
- **Or by email:** `saedmagdysms@gmail.com`, with "Titan Launcher security" in the subject.

Please include:
1. What the problem is, in plain words.
2. How to make it happen (steps, or a small example).
3. Which version you tested (Settings screen, or `versionName` in `AndroidManifest.xml`).

You will get an answer as soon as possible. Please give a reasonable time to release a fix
before telling anyone else about the problem.

## Which versions get fixes

Only the latest release on GitHub. Cars are updated through the in-app updater.

## Especially important

- Anything that lets another app or a web page reach `AndroidBridge` (the screen's line to the car).
- Anything that can open the trunk, or change other car controls, while the car is moving.
- Anything that can make the updater install a file that did not come from this repository's Releases.
