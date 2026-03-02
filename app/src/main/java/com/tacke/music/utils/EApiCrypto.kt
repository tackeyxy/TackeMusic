package com.tacke.music.utils

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object EApiCrypto {
    
    private const val EAPI_KEY = "e82ckenh8dichen8"
    
    private fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    private fun aesEncryptEcb(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it).uppercase() }
    }
    
    fun encrypt(urlPath: String, data: JSONObject): String {
        val dataJson = data.toString()
        val message = "nobody${urlPath}use${dataJson}md5forencrypt"
        val digest = md5(message)
        val dataBlock = "${urlPath}-36cd479b6b5-${dataJson}-36cd479b6b5-${digest}"
        return aesEncryptEcb(dataBlock, EAPI_KEY)
    }
}
