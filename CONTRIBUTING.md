# Contributing

Media Lens welcomes focused pull requests, bug reports and practical feature suggestions. Keep changes scoped, explain the user-facing result, and include the checks you ran in the pull request description.

## Checks

Run the JavaScript project checks from the repository root:

```sh
npm run check
npm test
```

The package test script runs the JavaScript tests and companion tests. The Python companion tests can also be run directly through unittest discovery:

```sh
python3 -m unittest discover -s tests -p 'native_test.py'
```

For the Android project, run the debug build from the Android project root:

```sh
./gradlew assembleDebug
```

## Keep private data out of commits

Do not commit personal media, downloaded files, browser profiles, private logs, cookies, tokens, credentials or other personal data. Use small fixtures and sanitised logs for reproducible tests.

## Licence

Media Lens is licensed under the GPL-3.0-or-later licence. Contributions should be compatible with that licence.
