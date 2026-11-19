package com.omsweb.fees;

import static com.omsweb.fees.ApiMessages.text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SecurityAccounts implements UserDetailsService {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._@-]{3,64}");
    private static final Map<String, Role> DEMO = Map.of(
        "operator", Role.OPERATOR, "finance", Role.FINANCE, "auditor", Role.AUDITOR, "admin", Role.ADMIN
    );

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AuditTrail audit;
    private final String demoPasswordHash;

    SecurityAccounts(JdbcClient jdbc, PasswordEncoder passwords, AuditTrail audit,
                     @Value("${oms.demo.enabled:true}") boolean demoEnabled) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        demoPasswordHash = demoEnabled ? passwords.encode("Omsweb-Demo-2026!") : null;
    }

    enum Role { OPERATOR, FINANCE, AUDITOR, ADMIN }

    record Account(String username, Role role) {}

    @Override
    public UserDetails loadUserByUsername(String value) {
        String username = normalize(value);
        return jdbc.sql("SELECT username, role, password_hash FROM app_users WHERE username = :username")
            .param("username", username)
            .query((row, number) ->
                details(row.getString("username"), Role.valueOf(row.getString("role")), row.getString("password_hash")))
            .optional()
            .orElseGet(() -> {
                Role demoRole = DEMO.get(username);
                if (demoPasswordHash != null && demoRole != null) {
                    return details(username, demoRole, demoPasswordHash);
                }
                throw new UsernameNotFoundException("Account not found");
            });
    }

    List<Account> list() {
        var accounts = new ArrayList<>(jdbc.sql("SELECT username, role FROM app_users")
            .query((row, number) -> new Account(row.getString("username"), Role.valueOf(row.getString("role"))))
            .list());
        if (demoPasswordHash != null) {
            DEMO.forEach((username, role) -> {
                if (accounts.stream().noneMatch(account -> account.username().equals(username))) {
                    accounts.add(new Account(username, role));
                }
            });
        }
        accounts.sort(Comparator.comparing(Account::username));
        return accounts;
    }

    @Transactional
    public Account create(String value, String password, Role role) {
        String username = normalize(value);
        if (!USERNAME.matcher(username).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, text(
                "账号须为 3 至 64 个字符，可使用字母、数字、点、下划线、@ 和连字符。",
                "Enter a username of 3 to 64 letters, digits, periods, underscores, @ signs or hyphens."
            ));
        }
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException(HttpStatus.BAD_REQUEST, text(
                "密码须至少为 12 个字符，且不得超过 72 个 UTF-8 字节。",
                "Enter a password of at least 12 characters and no more than 72 UTF-8 bytes."
            ));
        }
        if ((demoPasswordHash != null && DEMO.containsKey(username)) ||
                jdbc.sql("""
                    INSERT INTO app_users (username, password_hash, role) VALUES (:username, :hash, :role)
                    ON CONFLICT (username) DO NOTHING
                    """)
                    .param("username", username)
                    .param("hash", passwords.encode(password))
                    .param("role", role.name())
                    .update() == 0) {
            throw new AppException(HttpStatus.CONFLICT, text("账号已存在。", "This username already exists."));
        }
        var account = new Account(username, role);
        audit.record("ACCOUNT_CREATED", "USER", username, null, account);
        return account;
    }

    boolean hasAdministrator() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_users WHERE role = 'ADMIN')").query(Boolean.class).single();
    }

    private static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private UserDetails details(String username, Role role, String hash) {
        return User.withUsername(username).password(hash).roles(role.name()).build();
    }
}
