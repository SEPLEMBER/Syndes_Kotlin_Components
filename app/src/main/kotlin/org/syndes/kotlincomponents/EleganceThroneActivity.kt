package org.syndes.kotlincomponents

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EleganceThroneActivity : AppCompatActivity() {

    private lateinit var passwordField: EditText
    private lateinit var textField: EditText
    private lateinit var codingButton: Button
    private lateinit var uncodingButton: Button
    private lateinit var clearButton: Button
    private lateinit var exitButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elegance_throne)

        passwordField = findViewById(R.id.passwordField)
        textField = findViewById(R.id.textField)
        codingButton = findViewById(R.id.codingButton)
        uncodingButton = findViewById(R.id.uncodingButton)
        clearButton = findViewById(R.id.clearButton)
        exitButton = findViewById(R.id.exitButton)

        codingButton.setOnClickListener { codingFlow() }
        uncodingButton.setOnClickListener { uncodingFlow() }
        clearButton.setOnClickListener { clearFields() }
        exitButton.setOnClickListener { finishAffinity() }
    }

    private fun codingFlow() {
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
            toast(e.message ?: "Coding failed.")
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun uncodingFlow() {
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
            toast(e.message ?: "Uncoding failed.")
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
