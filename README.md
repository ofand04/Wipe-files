# WipeFiles

WipeFiles is an Android file-handling utility designed to let users select files, run an encryption-and-overwrite then delete file.

## What the app does

- **Select files:** Uses Android's system document picker to choose one or more files.
- **Storage access:** Explains and directs the user to Android Settings for special all-files access where supported. The user must grant access; the app cannot grant it automatically.
- **AES-256-GCM encryption workflow:** The intended workflow generates a random 256-bit key and encrypts file content using AES-GCM before proceeding to the next stage.
- **Key clearing:** The app attempts to clear the in-memory key byte array after encryption. This is best-effort only: Java, the runtime, or the operating system may retain copies in memory.
- **Overwrite passes:** Allows configuration of repeated random-data writes where the selected storage provider permits writing.
- **Filename changes and deletion:** Attempts filename changes and deletion through Android's document APIs when the provider supports those operations.
- **Progress and operation log:** Displays current stage, progress, and per-file success or failure messages.
- **Cancellation:** Allows cancellation at supported checkpoints.

## What “AES-256-GCM” means

AES-256-GCM is an authenticated encryption mode using a 256-bit AES key. It converts readable file content into ciphertext. If the key is irretrievably lost, that ciphertext cannot ordinarily be decrypted with that key.

WipeFiles does **not** preserve the encryption key for later recovery. Therefore, if the encryption stage completes and the key is cleared, the original content may become unrecoverable. Treat the operation as destructive and keep a separate backup of anything important.

Clearing a Java byte array is not a guarantee that every copy of the key has been removed from memory. The key is not literally “burned” from every location in the device.

## Important storage limitations

Android storage access depends on Android version, permissions, and the storage provider. Granting all-files access does not automatically give the app write access to every URI selected in the system picker.

A provider may refuse writing, renaming, syncing, or deletion. Files stored on flash storage, SSD-like media, cloud-backed providers, or systems with snapshots may retain older physical copies. For these reasons, the app cannot guarantee physical erasure or secure deletion.

If an operation fails midway, a file may be partially changed. Always read the operation log and do not interpret a failed operation as a successful wipe.

## Permissions and user control

- The system file picker grants access to the files the user chooses, subject to the provider's rules.
- On supported Android versions, all-files access is a special permission enabled manually in system Settings.
- The app should report denied or unsupported operations.

## Safety checklist

1. Back up important data.
2. Use disposable test files first.
3. Confirm the selected files and settings before starting.
4. Watch the live operation log for errors.
5. Do not rely on this app as a guarantee of forensic-grade data sanitization.
