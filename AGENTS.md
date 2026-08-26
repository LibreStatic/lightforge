# Project Rules

- All commits must be written in English, regardless of the language used in the prompt.
- The application's core strings must be written in English and implemented with full localization support for English, Spanish, French, Portuguese, Italian, and other common languages.
- UI foreground/background combinations must use matching Material semantic color-role pairs (for example, `secondaryContainer` with `onSecondaryContainer`). Custom content must inherit the container content color unless an explicitly validated pair is required. Validate contrast in light, dark, and dynamic color themes; do not use unrelated semantic roles or hard-coded colors.
