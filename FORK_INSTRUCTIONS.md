# Apply these changes to your fork

This ZIP already includes the complete modified source. Alternatively, keep your
fork checkout and apply the cumulative APPLY_TO_FORK.patch from this ZIP:

```sh
cd /path/to/your/BitChord-fork
git switch -c feature/chords-lyrics
git apply --check /path/to/extracted/BitChord-chords-mvp/APPLY_TO_FORK.patch
git apply /path/to/extracted/BitChord-chords-mvp/APPLY_TO_FORK.patch
```

This patch includes the chord detector, inline lyric UI, Simplify, separate app
IDs, tests and build script. It is based on upstream commit
21356206de5594ca0bdaf4680ca2db68a2287729. If your newly forked version differs,
--check may report conflicts. Ask Codex to port the patch instead of forcing it.
Then follow BUILD_CHORDS_MVP.md. No changes have been pushed to your GitHub account.
