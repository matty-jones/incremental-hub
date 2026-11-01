# Pixel Fold acceptance checklist (S5b)

Do not run these steps on the production Shark profile. Use a disposable profile, or skip the step and leave it NOT RUN. The production save is not a drill target.

| Step | What to confirm | Result |
| --- | --- | --- |
| Vault browsing while Shark is running | Open the vault from the in-game menu. Shark stays on the same WebView. History rows are readable. | NOT RUN |
| Fold and unfold with the vault open | Fold, then unfold, with the vault still open. The game document is not reloaded. | NOT RUN |
| Protect and export outside app storage | Protect one snapshot. Export the game through the document picker to a file that is not under the app's private storage. | NOT RUN |
| Import without changing the live game | Import that archive. The live Shark session and the Room pointer stay on the current save. | NOT RUN |
| Restart and workstation pointer | Force-stop, reopen, and confirm the Room pointer. Confirm the workstation pointer did not move to an older or incomparable save. | NOT RUN |

No Pixel Fold was attached for S5b. The attached devices were not used for this checklist.
