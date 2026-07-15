package org.syndes.kotlincomponents

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EleganceThroneActivity : AppCompatActivity() {

    private lateinit var passwordField: EditText
    private lateinit var textField: EditText
    private lateinit var encryptButton: Button
    private lateinit var decryptButton: Button
    private lateinit var clearButton: Button
    private lateinit var exitButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elegance_throne)

        passwordField = findViewById(R.id.passwordField)
        textField = findViewById(R.id.textField)
        encryptButton = findViewById(R.id.encryptButton)
        decryptButton = findViewById(R.id.decryptButton)
        clearButton = findViewById(R.id.clearButton)
        exitButton = findViewById(R.id.exitButton)

        encryptButton.setOnClickListener { encryptFlow() }
        decryptButton.setOnClickListener { decryptFlow() }
        clearButton.setOnClickListener { clearFields() }
        exitButton.setOnClickListener { finishAffinity() }
    }

    private fun encryptFlow() {
        val password = passwordField.text?.toString().orEmpty()
        val plaintext = textField.text?.toString().orEmpty()

        if (password.isBlank()) {
            toast("Password is empty.")
            return
        }

        if (plaintext.isBlank()) {
            toast("Text is empty.")
            return
        }

        val passwordChars = password.toCharArray()
        try {
            val aes = Secure.Coding(passwordChars, plaintext)
            val elegant = Secure2.Coding(aes).orEmpty()

            textField.setText(elegant)
            if (elegant.isNotEmpty()) {
                textField.setSelection(elegant.length)
            }
        } catch (e: Exception) {
            toast(e.message ?: "Encryption failed.")
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun decryptFlow() {
        val password = passwordField.text?.toString().orEmpty()
        val input = textField.text?.toString().orEmpty()

        if (password.isBlank()) {
            toast("Password is empty.")
            return
        }

        if (input.isBlank()) {
            toast("Text is empty.")
            return
        }

        val passwordChars = password.toCharArray()
        try {
            val decoded = Secure2.Uncoding(input).orEmpty()
            val plaintext = Secure.Uncoding(passwordChars, decoded).orEmpty()

            textField.setText(plaintext)
            if (plaintext.isNotEmpty()) {
                textField.setSelection(plaintext.length)
            }
        } catch (e: Exception) {
            toast(e.message ?: "Decryption failed.")
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun clearFields() {
        passwordField.text?.clear()
        textField.text?.clear()
        passwordField.requestFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
