# Privacy Policy for VaultPass

**Effective Date:** June 9, 2026

## 1. Introduction
Welcome to VaultPass. We respect your privacy and are committed to protecting it. This Privacy Policy explains our practices regarding the collection, use, and disclosure of information when you use the VaultPass Android application ("the App"). 

VaultPass is designed from the ground up to be an offline-first password manager. We believe your data belongs exclusively to you. To ensure this, VaultPass operates locally on your device without requiring an internet connection to function. 

## 2. Information Stored by the App
VaultPass allows you to store personal information, including but not limited to passwords, usernames, URLs, notes, and custom fields. **We do not collect, transmit, or have access to any of this information.** 

VaultPass does not require user registration or account creation. There is no central database of users, and you will never be asked to provide an email address, phone number, or personal identifier to use the App.

## 3. Local Device Storage
All data you input into VaultPass is stored strictly and entirely on your local device. 
* **No Cloud Synchronization:** The App does not sync your data to any proprietary or third-party cloud servers. 
* **No Remote Databases:** There are no backend servers associated with the App that receive your data.
* **No external telemetry or analytics:** The App does not monitor your behavior, log your actions, or send usage statistics or crash reports to us or any third parties.

## 4. Encryption and Security
Your data is protected using AES-GCM encryption before it is saved to your device's local storage. VaultPass encrypts vault data locally on the device before it is written to storage.
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

## 7. Third-Party Services
VaultPass is built to function independently of third-party network services. 
* **No Advertising:** The App does not contain any advertisements, nor does it use advertising SDKs.
* **No Tracking:** The App does not use tracking SDKs, remarketing tools, or social media integrations.
* **No Data Selling:** Because we do not collect your data, we do not (and cannot) sell, rent, or share your data with any third parties.

## 8. Children's Privacy
VaultPass does not knowingly collect any personal information from children or any other users. Because the App operates entirely offline and requires no registration, it can be used by individuals of any age. However, parents and guardians should be aware that the App stores sensitive information locally on the device. VaultPass is not directed toward children under the age of 13. The application does not knowingly collect personal information from children or any other users.

## 9. Data Retention
Because VaultPass stores data exclusively on your device, you maintain complete control over data retention. Your data is retained for as long as you keep the App installed or keep your exported backup files. You can delete all your data at any time by securely deleting the App and any associated backup files from your device. Copied credentials may be automatically removed from the system clipboard after a short period.

*(Note: The App features a Recycle Bin that soft-deletes items. Items in the Recycle Bin are permanently and automatically removed from your local device after 7 days, or they can be manually permanently deleted by you at any time).*

## 10. User Responsibilities
Your privacy and security depend heavily on your own practices. By using VaultPass, you acknowledge that you are responsible for:
* Choosing a strong, unique Master Password that you do not use anywhere else.
* Remembering your Master Password, as it cannot be recovered or reset by us.
* Securing the physical access to your device.
* Managing and securing any unencrypted backup files you choose to export.
* Ensuring your device's operating system is kept up-to-date and free from malware.

## 11. Changes to This Privacy Policy
We may update this Privacy Policy from time to time to reflect changes in our practices or the App's features. Any changes will be posted within this document. Because we do not collect your contact information, we cannot notify you individually of changes. We encourage you to review this Privacy Policy periodically.

## 12. Contact Information
If you have any questions, concerns, or feedback regarding this Privacy Policy or the security practices of VaultPass, please contact through official email - armaanweb100@gmail.com or official GitHub repository.
