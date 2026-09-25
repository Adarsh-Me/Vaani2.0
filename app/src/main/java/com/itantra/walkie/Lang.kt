package com.itantra.walkie

/** 11 supported langs. Codes match IndicTrans2 FLORES tags. */
enum class Lang(val tag: String, val label: String, val native: String) {
    EN("eng_Latn", "English", "English"),
    HI("hin_Deva", "Hindi", "हिन्दी"),
    BN("ben_Beng", "Bengali", "বাংলা"),
    GU("guj_Gujr", "Gujarati", "ગુજરાતી"),
    KN("kan_Knda", "Kannada", "ಕನ್ನಡ"),
    ML("mal_Mlym", "Malayalam", "മലയാളം"),
    MR("mar_Deva", "Marathi", "मराठी"),
    PA("pan_Guru", "Punjabi", "ਪੰਜਾਬੀ"),
    TA("tam_Taml", "Tamil", "தமிழ்"),
    TE("tel_Telu", "Telugu", "తెలుగు"),
    OR("ory_Orya", "Odia", "ଓଡ଼ିଆ");
}

enum class Voice { F, M }
