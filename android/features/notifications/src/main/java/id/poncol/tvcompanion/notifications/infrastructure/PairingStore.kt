package id.poncol.tvcompanion.notifications.infrastructure
import android.content.Context
import java.security.SecureRandom

fun pairingPin(context: Context): String {
    val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    val saved = prefs.getString("token", null)
    if (saved != null && saved.matches(Regex("[0-9]{6}"))) return saved
    // Migrate old long tokens once, then retain the PIN across launches and upgrades.
    return (100000 + SecureRandom().nextInt(900000)).toString().also {
        prefs.edit().putString("token", it).apply()
    }
}
