package com.caizhixing.feedback.model

class ApiException(val code: Int, override val message: String) : RuntimeException(message)
