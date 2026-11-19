package com.omsweb.fees;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

@Configuration(proxyBeanMethods = false)
class ApiLocaleConfiguration {
    private static final Locale CHINESE = Locale.forLanguageTag("zh-HK");
    private static final Locale ENGLISH = Locale.forLanguageTag("en-HK");

    @Bean
    LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                String language = request.getParameter("lang");
                if (language != null) {
                    switch (Locale.forLanguageTag(language).getLanguage()) {
                        case "en": return ENGLISH;
                        case "zh": return CHINESE;
                        default: break;
                    }
                }
                return super.resolveLocale(request);
            }
        };
        resolver.setDefaultLocale(CHINESE);
        resolver.setSupportedLocales(List.of(CHINESE, ENGLISH, Locale.CHINESE, Locale.ENGLISH));
        return resolver;
    }
}
