package at.rueckgr

import at.favre.lib.crypto.bcrypt.BCrypt
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.ktor.server.auth.*
import java.io.File
import java.io.IOException

class AuthenticationService private constructor() {
    companion object {
        private var instance: AuthenticationService? = null

        fun getInstance(): AuthenticationService {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AuthenticationService()
                    }
                }
            }
            return instance!!
        }
    }

    fun authenticate(credentials: UserPasswordCredential): UserIdPrincipal? {
        val file = File("users.yaml") // TODO
        if (!file.exists()) {
            // TODO
            return null
        }

        val config = try {
            ObjectMapper(YAMLFactory())
                .findAndRegisterModules()
                .readValue(file, Users::class.java)
        }
        catch (e: IOException) {
            e.printStackTrace()
            // TODO
            return null
        }

        val user = config.users.find { it.username == credentials.name &&
                BCrypt.verifyer().verify(credentials.password.toCharArray(), it.password).verified} ?: return null
        return UserIdPrincipal(user.username)
    }
}

data class User(val username: String, val password: String)

data class Users(val users: List<User>)
