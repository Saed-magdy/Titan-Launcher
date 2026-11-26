<!-- Please fill in every part. Write in plain words: the reviewer may not be a programmer. -->

## What was wrong
<!-- What did the driver see, or what was the problem in the code? -->

## What this PR changes
<!-- One change per PR. List the files or screens it touches. -->

## Action needed by the reviewer
<!-- Anything the reviewer must do on their PC or on GitHub after merging
     (new file to create, folder to change, setting to switch).
     Give the exact steps. Write "None" if nothing is needed. -->

## How to test it in the car
1.
2.

## Checklist
- [ ] This PR goes into `dev` (not `main`).
- [ ] It contains one change only.
- [ ] It builds: `build_apk.ps1 -TestSign` finished with BUILD SUCCESSFUL.
- [ ] It does not raise the version number (that happens only when `dev` is released to `main`).
- [ ] It contains no signing keys, passwords or APK files.
- [ ] Text that comes from outside (app names, station names, song titles) is shown with `escapeHtml()` or `textContent`.
- [ ] **Contributor agreement:** I wrote this contribution (or have the right to send it). I agree that it is licensed under this project's [LICENSE](https://github.com/Saed-magdy/Titan-Launcher/blob/dev/LICENSE), and that Dr. Saed may also license it under other terms in the future, including commercial ones.
