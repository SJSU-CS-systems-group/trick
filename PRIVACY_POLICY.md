# Privacy Policy

**Last updated:** April 17, 2026

Trick is a peer-to-peer encrypted messaging application developed at San José State University under the SJSU Computer Science Systems Group. This policy explains what data the app accesses, how it is used, and what it does not do.

---

## 1. Summary

Trick does **not** collect, transmit, or store any personal data on external servers. All communication stays on your device and is sent directly to nearby devices over a local Wi-Fi Aware connection.

---

## 2. Data We Access

### Messages and Media
- Messages and photos you send are **end-to-end encrypted** using the Signal Protocol (Double Ratchet algorithm with Kyber post-quantum cryptography) before transmission.
- Message data is stored **locally on your device only**.
- We do not have access to the content of your messages.

### Contacts
- The app reads your device contacts to display a list of known peers.
- Contacts may be created or updated locally when you exchange keys with another user via QR code.
- Contact data is **never uploaded to any server**.

### Location
- The app requests location permission because Android requires it to use WiFi Aware (peer-to-peer device discovery).
- Your location is **not tracked, stored, or transmitted** by the app.
- The permission is used solely to satisfy the Android OS requirement for WiFi Aware functionality.

### Camera
- The camera is used exclusively to scan QR codes for cryptographic key exchange between devices.
- No photos are taken or stored by the key exchange process.

### WiFi and Network
- The app uses WiFi Aware to discover and connect to nearby devices.
- Connections are direct device-to-device — no internet connection is required or used for messaging.

### Cryptographic Keys
- Your identity keys are generated on-device and stored in the Android Keystore (hardware-backed secure storage).
- Private keys **never leave your device**.

---

## 3. Data We Do NOT Collect

- We do not collect analytics or usage statistics.
- We do not use advertising SDKs.
- We do not transmit crash reports to external services.
- We do not have access to your account credentials, passwords, or payment information.
- We do not operate any servers that receive your messages or personal data.

---

## 4. Data Sharing

We do not share any data with third parties. There are no third-party SDKs in Trick that collect personal information.

---

## 5. Data Retention

All data (messages, keys, contacts) is stored locally on your device. Uninstalling the app removes all locally stored data. We have no server-side copies.

---

## 6. Children's Privacy

Trick is not directed at children under 13. We do not knowingly collect information from children.

---

## 7. Security

- All messages are end-to-end encrypted with Signal Protocol before transmission.
- Cryptographic private keys are stored in Android Keystore hardware-backed secure storage.
- WiFi Aware connections use pre-shared key (PSK) authentication.

---

## 8. Changes to This Policy

We may update this policy as the app evolves. Changes will be reflected by updating the "Last updated" date above. Continued use of the app after changes constitutes acceptance of the updated policy.

---

## 9. Contact

If you have questions about this privacy policy, please contact the Trick development team at:

**San José State University Computer Science Systems Group**  
Email: [yihao.li@sjsu.edu] 
Email: [darren.shen@sjsu.edu]
Email: [ben.reed@sjsu.edu]

---

*Trick is an open-source research project. Source code is available at https://github.com/SJSU-CS-systems-group/trick.*
