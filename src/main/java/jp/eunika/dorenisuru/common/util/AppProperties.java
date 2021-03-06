package jp.eunika.dorenisuru.common.util;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {
	private int effectiveDaysOfTopics;
	private String bugsnagApiKey;
	private String encryptKey;
}
