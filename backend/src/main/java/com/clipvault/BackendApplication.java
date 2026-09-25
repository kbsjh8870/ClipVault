package com.clipvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ClipVault 백엔드 서버의 시작점(main 클래스).
 *
 * <p>여러 PC 사이에서 클립보드 텍스트를 동기화해 주는 API 서버다.
 * 트레이 앱은 REST API로 클립을 올리고, 서버는 WebSocket(STOMP)으로 같은 사용자의 다른 PC에 새 클립을 알려준다.</p>
 *
 * <ul>
 *   <li>{@code @SpringBootApplication}: 이 패키지(com.clipvault) 아래의 컨트롤러, 서비스, 설정 클래스를 자동으로 찾아 등록한다.</li>
 *   <li>{@code @EnableScheduling}: {@code @Scheduled}가 붙은 메서드(만료 클립 삭제 배치, {@link com.clipvault.clip.ClipCleanupJob})가
 *       정해진 시간에 자동 실행되도록 켜 준다. 이 어노테이션이 없으면 배치가 절대 돌지 않는다.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	/** 스프링 부트 애플리케이션을 띄운다. 내장 톰캣이 {@code PORT} 환경변수(기본 8080) 포트로 열린다. */
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
