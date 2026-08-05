===================================================
      Standalone Smartnav APK Signer
===================================================

This tool automates the process of aligning and signing Android APKs using AOSP platform keys. It is designed to be fully standalone and does NOT require the Android SDK to be installed on your system.

--- DIRECTORY STRUCTURE ---
Smartnav-APK-Signer/
├── sign_auto.bat       (Windows execution script)
├── sign_auto.sh        (Linux execution script)
├── Readme.txt
└── Tools/
    ├── apksigner, apksigner.bat, apksigner.jar
    ├── zipalign, zipalign.exe
    ├── platform.pk8        (Your private key)
    ├── platform.x509.pem   (Your public certificate)
    └── lib64/              (Libraries required for zipalign on Linux)

--- HOW TO RUN ---

[For Windows Users]
Method 1: Drag and Drop
Simply drag your unsigned .apk file from Windows Explorer and drop it directly onto `sign_auto.bat`.

Method 2: Command Line
Open Command Prompt and run the batch file with the path to your APK:
> sign_auto.bat C:\path\to\your\app.apk


[For Linux Users]
1. Open a terminal.
2. Run the bash script and pass the path to your unsigned APK as the argument:
$ ./sign_auto.sh /path/to/your/app.apk

Note: Ensure that the Linux binaries in the Tools/ folder have executable permissions (chmod +x).


--- OUTPUT ---
The script will automatically create a dedicated output folder next to your original APK (e.g., `app_output/`).
Inside this folder, you will find:
1. app_aligned.apk (The zipalign'd version)
2. app_platform_signed.apk (The final, securely signed version ready for installation)
