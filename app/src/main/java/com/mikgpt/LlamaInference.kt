package com.mikgpt

interface TokenCallback {
    fun onToken(token: String)
}

object LlamaInference {
    init {
        System.loadLibrary("mikgpt")
    }

    external fun loadModel(modelPath: String): Boolean
    external fun generate(prompt: String, grammar: String?, callback: TokenCallback): String
    external fun unloadModel()
}
