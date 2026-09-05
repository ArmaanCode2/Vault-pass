# Privacy Policy for VaultPass

**Effective Date:** June 9, 2026

## 1. Introduction
Welcome to VaultPass. We respect your privacy and are committed to protecting it. This Privacy Policy explains our practices regarding the collection, use, and disclosure of information when you use the VaultPass Android application ("the App"). 

VaultPass is designed from the ground up to be an offline-first password manager. We believe your data belongs exclusively to you. To ensure this, VaultPass operates locally on your device without requiring an internet connection to function. 

## 2. Information Stored by the App
VaultPass allows you to store personal information, including but not limited to passwords, usernames, URLs, notes, and custom fields. **We do not collect, transmit, or have access to any of this information.** 

VaultPass does not require user registration or account creation. There is no central database of users, and you will never be asked to provide an email address, phone number, or personal identifier to use the App.

## 3. Local Device Storage and System Backups
All data you input into VaultPass is stored strictly and entirely on your local device. 
* **No Developer Cloud Servers:** The App does not sync your data to any proprietary or developer cloud servers. VaultPass operates no cloud infrastructure and has zero access to your stored records.
* **No Remote Databases:** There are no backend servers associated with the App that receive your data.
* **No External Telemetry or Analytics:** The App does not monitor your behavior, log your actions, or send usage statistics or crash reports to us or any third parties.
* **Android System Cloud Backups:** VaultPass runs no cloud services of its own and cannot access your information. However, if you have enabled Android Auto-Backup or Google Drive backup on your personal device, the Android operating system may include app data in backups saved to your private Google account based on your device settings. You can control, manage, or turn off app backups at any time through your Android system settings under Google Backup.

## 4. Encryption and Security
All your vault data is protected using standard AES-GCM encryption before it is written to your device's local storage.
* **Master Password:** Your data is encrypted using a key derived from your Master Password. We do not know your Master Password, and it is never transmitted off your device.
* **No Recovery Backdoors:** Because your data is encrypted locally and we do not have your Master Password, **we cannot recover your data if you forget your Master Password.** 
* **Local Brute-Force Protection:** The App includes built-in cooldown timers to protect against local brute-force guessing attempts. 

While VaultPass employs strong, industry-standard cryptographic practices to protect your data, no software or device can be guaranteed to be entirely immune from compromise, especially if the underlying device operating system is compromised or rooted.

## 5. Import and Export Features
VaultPass provides utilities to import and export your vault data (in TXT, JSON, or encrypted VPEX formats) for your own backup purposes. 
* When you export your data, files are written to the location selected by the user through Android's file picker. 
* You are solely responsible for securing the exported files. If you export your vault in unencrypted formats (TXT or JSON), the information will be readable by anyone who gains access to that file. Encrypted VPEX exports remain encrypted until successfully imported and decrypted using the appropriate password.

## 6. Biometric Authentication
VaultPass supports biometric authentication (such as fingerprint or other supported biometric authentication methods) to unlock your vault, leveraging the Android Keystore system. 
* Biometric data (e.g., your fingerprint data) is managed entirely by your device’s operating system and hardware. 
* VaultPass requests the operating system to verify your identity; the App never collects, stores, or transmits your actual biometric data.

## 7. Android Autofill Service
VaultPass includes an optional Autofill service that helps you quickly fill in usernames and passwords inside other apps and web browsers.
* **How It Works:** When you turn on this feature in your Android system settings, the service checks the name of the app or website on your screen to find matching login details from your unlocked vault.
* **Temporary Processing:** This matching check happens entirely inside your phone in temporary device memory. The name of the app or website on your screen is never recorded, never stored permanently, and never sent anywhere.
* **User Control:** You can turn off or change the Autofill service at any time in your Android system settings.

## 8. Clipboard Safety
When you tap the copy button to copy a username or password, the text is temporarily placed onto your Android device clipboard so you can paste it where needed.
* **Automatic Clearing:** VaultPass attempts to automatically clear the copied item after a short time (configurable in the App's Settings).
* **Clipboard Awareness:** Other applications installed on your phone with clipboard permissions, or custom third-party keyboard apps, may be able to read text on the clipboard while it is active. We encourage you to only use trusted apps and keyboards on your device.

## 9. Third-Party Services
VaultPass is built to function independently of third-party network services. 
* **No Advertising:** The App does not contain any advertisements, nor does it use advertising SDKs.
* **No Tracking:** The App does not use tracking SDKs, remarketing tools, or social media integrations.
* **No Data Selling:** Because we do not collect your data, we do not (and cannot) sell, rent, or share your data with any third parties.

## 10. Children's Privacy
VaultPass is not intended for children under the age of 13. The App requires no registration and does not collect personal information from any user, including children. All data stored in the App remains strictly on the local device.

## 11. Data Retention
Because VaultPass stores data exclusively on your device, you maintain complete control over data retention. Your data is retained for as long as you keep the App installed or keep your exported backup files. You can delete all your data at any time by clearing app data or uninstalling the App and deleting any exported backup files from your device.

*(Note: The App features a Recycle Bin that soft-deletes items. Items in the Recycle Bin are permanently and automatically removed from your local device after 7 days, or they can be manually permanently deleted by you at any time).*

## 12. Your Privacy Rights (GDPR and CCPA)
Privacy laws like the General Data Protection Regulation (GDPR) and the California Consumer Privacy Act (CCPA) give users rights regarding their personal information, including the rights to view, change, export, and delete their data.

Because VaultPass does not collect or keep your data on any server, you have direct, complete control over these rights on your device:
* **Access and Change:** You can view and edit any entry directly in the App at any time.
* **Data Portability:** You can export your data at any time using the Export feature in Settings.
* **Deletion:** You can permanently delete individual entries using the Recycle Bin, or delete all data completely by clearing app data in Android settings or uninstalling the App from your device.

## 13. User Responsibilities
Your privacy and security depend heavily on your own practices. By using VaultPass, you acknowledge that you are responsible for:
* Choosing a strong, unique Master Password that you do not use anywhere else.
* Remembering your Master Password, as it cannot be recovered or reset by us.
* Securing the physical access to your device.
* Managing and securing any unencrypted backup files you choose to export.
* Ensuring your device's operating system is kept up-to-date and free from malware.

## 14. Changes to This Privacy Policy
We may update this Privacy Policy from time to time to reflect changes in our practices or the App's features. Any changes will be posted within this document. Because we do not collect your contact information, we cannot notify you individually of changes. We encourage you to review this Privacy Policy periodically.

## 15. Contact Information
If you have any questions, concerns, or feedback regarding this Privacy Policy or the security practices of VaultPass, please contact us:
* Email: armaanweb100@gmail.com
* GitHub: https://github.com/ArmaanCode2/Vault-pass

