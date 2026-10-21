package com.veriqra.app.config;

import com.veriqra.app.web.FaceVerificationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web layer wiring.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final FaceVerificationInterceptor faceVerificationInterceptor;

    public WebMvcConfig(FaceVerificationInterceptor faceVerificationInterceptor) {
        this.faceVerificationInterceptor = faceVerificationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(faceVerificationInterceptor)
                .addPathPatterns("/api/student/**")
                .excludePathPatterns("/api/student/biometric/**");
    }
}
