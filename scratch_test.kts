import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

fun main() {
    val dek = ByteArray(32)
    SecureRandom().nextBytes(dek)
    
    val kek = ByteArray(32)
    SecureRandom().nextBytes(kek)
    
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey = SecretKeySpec(kek, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val iv = cipher.iv
    val encryptedData = cipher.doFinal(dek)
    val combined = iv + encryptedData
    val wrapped = Base64.getEncoder().encodeToString(combined)
    
    println("Wrapped: $wrapped")
    
    val decoded = Base64.getDecoder().decode(wrapped)
    val div = decoded.copyOfRange(0, 12)
    val ddata = decoded.copyOfRange(12, decoded.size)
    
    val dcipher = Cipher.getInstance("AES/GCM/NoPadding")
    dcipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, div))
    try {
        val ddek = dcipher.doFinal(ddata)
        println("Decrypted perfectly! Match: ${ddek.contentEquals(dek)}")
    } catch(e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}
