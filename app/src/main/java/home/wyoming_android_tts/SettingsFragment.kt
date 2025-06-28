package home.wyoming_android_tts

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat // Already present, ensure no other preference import is conflicting

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}
