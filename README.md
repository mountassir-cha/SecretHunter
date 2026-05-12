# SecretHunter 🔍

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Room](https://img.shields.io/badge/Room-032541?style=for-the-badge&logo=sqlite&logoColor=white)](https://developer.android.com/training/data-storage/room)

Application Android qui analyse des **fichiers accessibles** pour détecter des **informations sensibles** via des expressions régulières : mots de passe en clair, JWT, clés API, tokens, e-mails, numéros, etc.

> ⚠️ **Avertissement éthique** : Cette application doit être utilisée **uniquement** sur des appareils dont vous êtes propriétaire ou avec **autorisation explicite**. Les données traitées peuvent être personnelles ou soumises au RGPD.

## ✨ Fonctionnalités

- 🔎 **Scan de fichiers** : parcours des fichiers accessibles (stockage privé, médias)
- 🎯 **Détection intelligente** : 10+ règles prédéfinies (passwords, API keys, tokens, JWT, etc.)
- 📊 **Classification par sévérité** : CRITICAL / HIGH / MEDIUM / LOW
- 💾 **Stockage local** : historique des détections avec Room Database
- 📱 **Compatibilité Android** : API 21+ (Android 5.0 à 14+)
- 🔒 **Respect des permissions** : approche SAF (Storage Access Framework) moderne

## 📋 Règles de détection incluses

| Catégorie | Exemples | Sévérité |
|-----------|----------|----------|
| Mots de passe | `password = "..."`, `secret: "..."` | CRITICAL |
| JWT Tokens | `eyJhbGciOiJ...` | HIGH |
| API Keys | `api_key=`, `apikey:` | HIGH |
| Clés AWS | `AKIA...` | HIGH |
| Tokens OAuth | `ya29.`, `ghp_` (GitHub) | HIGH |
| Clés privées | `-----BEGIN RSA PRIVATE KEY-----` | CRITICAL |
| E-mails | `user@domain.com` | MEDIUM |
| Numéros de téléphone | `+33 6 12 34 56 78` | LOW |

*(Fichier configurable : `assets/regex_rules.json`)*

## 🚀 Installation

### Prérequis

- [Android Studio](https://developer.android.com/studio) (recommandé)
- JDK 17-21 (JBR fourni avec Android Studio)
- Gradle (wrapper inclus)

### Compilation

**Via Android Studio :**
1. `File → Open` → sélectionner le dossier `SecretHunter`
2. Cliquer sur **Run** (triangle vert)

**Via ligne de commande (Windows) :**
```cmd
cd chemin/vers/SecretHunter
gradlew.bat assembleDebug