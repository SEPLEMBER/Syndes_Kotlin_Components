# Syndes KComponents

Syndes Kotlin Components is a part of the Syndes Android environment.
It represents a single Android application that contains multiple tools implemented as separate activities.

This module provides a set of Kotlin-based utilities focused on text processing, file management, and security analysis

## Included Tools

1. Rust Text Editor. A lightweight text editor with enhanced privacy and security features.

2. Batch Text Replacement Tool. Allows fast bulk replacement of text across multiple files.

3. Batch File Renaming Tool. A simple utility for renaming files in bulk.

4. AppsCheck. A security-oriented tool designed to detect potentially suspicious bloatware applications.

## Navigation & Launching

Each utility can be launched from any activity launcher

Direct navigation between tools is also possible via the Terminal
(see details in the main Syndes project)

## Important

The app is signed with a **test signature** for technical reasons. If you are concerned, you can rebuild the APK manually. Since the signature itself does not protect against tampering, a standard test one was used.

It is recommended to download the APK from **Syndes Project** (Github, profile) or from the **Releases** section of this repository. Despite using a test signature, the app is a **release build** with debugging disabled.
