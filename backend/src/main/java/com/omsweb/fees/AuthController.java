package com.omsweb.fees;

import static com.omsweb.fees.ApiMessages.text;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final SecurityAccounts accounts;

    AuthController(SecurityAccounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/session")
    Session session(HttpServletRequest request, Authentication authentication) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new Session(account(authentication), token.getToken(), token.getHeaderName());
    }

    @GetMapping("/users")
    List<SecurityAccounts.Account> users() {
        return accounts.list();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    SecurityAccounts.Account createUser(@RequestBody NewAccount input) {
        if (input.username() == null || input.password() == null || input.role() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, text(
                "请填写账号、密码及角色。", "Enter a username and password, and select a role."
            ));
        }
        return accounts.create(input.username(), input.password(), input.role());
    }

    private SecurityAccounts.Account account(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> new SecurityAccounts.Account(
                authentication.getName(), SecurityAccounts.Role.valueOf(authority.substring(5))))
            .findFirst()
            .orElse(null);
    }

    record Session(SecurityAccounts.Account user, String csrfToken, String csrfHeader) {}

    record NewAccount(String username, String password, SecurityAccounts.Role role) {}
}
