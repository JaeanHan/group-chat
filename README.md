# Group Chat

오픈 채팅방 서버를 설계하고 구현하기 위한 저장소입니다.

현재 문서는 프로덕션에서 재사용 가능한 공통 채팅방 모듈을 목표로, 도메인 상태, command/query 계약, 실시간 이벤트 전파, outbox 전달 보장을 정리합니다.

## 백엔드 설계 문서

1. [core.md](./chat-server/docs/core.md)  
  오픈 채팅방의 핵심 문제와 설계 원칙을 설명합니다.

2. [domain-model.md](./chat-server/docs/domain-model.md)  
  채팅방, 참여, 메시지, 읽음 상태, 실시간 연결의 도메인 모델을 정의합니다.

3. [commands.md](./chat-server/docs/commands.md)  
  상태를 변경하는 command의 사전 조건, 상태 변화, 이벤트 생성 기준을 정의합니다.

4. [queries.md](./chat-server/docs/queries.md)  
  상태를 변경하지 않는 조회의 권한과 조회 기준을 정의합니다.

5. [events.md](./chat-server/docs/events.md)  
  서버에서 클라이언트로 전파하는 WebSocket 이벤트의 payload와 적용 기준을 정의합니다.

6. [outbox.md](./chat-server/docs/outbox.md)  
  DB에 확정된 변경을 WebSocket으로 안정적으로 전달하기 위한 Outbox relay 정책을 정의합니다.

## 프론트엔드 설계 문서

1. [user-flows.md](./chat-client/docs/user-flows.md)  
  내가 참여한 방 목록, 참여 가능한 방 조회, 방 채팅 페이지에서 사용자가 room을 발견하고 참여하고 메시지를 주고받는 흐름을 정의합니다.

2. [core.md](./chat-client/docs/core.md)  
  사용자 흐름에서 반복되는 오픈 채팅방 프론트엔드의 핵심 UX/상태 원칙을 설명합니다.

3. [domain-view.md](./chat-client/docs/domain-view.md)  
  Core 원칙을 클라이언트 도메인 상태, pending 상태, capability, 보정 기준으로 구체화합니다.

4. [queries.md](./chat-client/docs/queries.md)  
  서버 조회 결과를 클라이언트 도메인 모델에 어떻게 반영하는지 정의합니다.

5. [commands.md](./chat-client/docs/commands.md)  
  사용자 쓰기 행동과 pending 상태, 서버 확정 결과 반영 기준을 정의합니다.

6. [screen-model.md](./chat-client/docs/screen-model.md)  
  프론트엔드 도메인 모델을 실제 화면 단위로 조합하는 기준을 정의합니다.

7. [react-state-model.md](./chat-client/docs/react-state-model.md)  
  화면별 상태를 Query Cache, Mutation Cache, Client Domain Store, Realtime Store, Auth/Router, Component State에 배치하고 React Hook과 렌더링 의존성으로 연결하는 기준을 정의합니다.

## 부하 산정

초기 채팅 시스템은 RabbitMQ·Redis Pub/Sub 같은 외부 브로커 없이 다음 구조로 운영합니다.

Spring Simple Broker가 서버 프로세스 내부에서 방별 구독 정보와 메시지 전파를 담당합니다.

| 항목 | 기준 |
|---|---:|
| 최대 동시 접속자 | 500명 |
| 방당 구독자 | 50명 |
| 최대 활성 방 | 10개 |
| 평균 메시지율 | 방당 1 msg/s |
| 지속 피크 | 방당 5 msg/s |

한글 20글자는 UTF-8 기준 원본 약 60바이트입니다. JSON, STOMP header, WebSocket frame, 식별자와 전송 계층 overhead를 포함하면 실제 outbound 전송은 약 **0.5~1KiB**로 가정합니다.

```text
메시지 1개당 트래픽
≈ 0.5~1 KiB × 50명
≈ 25~50 KiB
```

전체 500명이 50명씩 10개 방에 접속한 경우:

| 상황 | 원본 메시지 | 실제 전파 횟수 | 예상 트래픽 |
|---|---:|---:|---:|
| 평균: 방당 1 msg/s | 10 msg/s | 500 deliveries/s | 약 2~4 Mbps |
| 피크: 방당 5 msg/s | 50 msg/s | 2,500 deliveries/s | 약 11~20 Mbps |
| 짧은 버스트: 방당 10 msg/s | 100 msg/s | 5,000 deliveries/s | 약 22~40 Mbps |

따라서 현재 규모에서는 다음 값이 실제 부하를 나타닙니다.

```text
원본 메시지율 × 구독자 수 × 메시지 크기
```

#### 운영 조건

- 사용자별 평균 1 msg/s, 순간 burst 3~5개
- 방별 지속 5 msg/s, 짧은 burst 10 msg/s
- 메시지 본문 및 STOMP frame 크기 제한
- 느린 클라이언트의 send buffer와 send time 제한
- `typing`, `read receipt`, `presence` 이벤트 throttle 및 병합
- `roomSequence` 기반 누락 메시지 복구

부하 시험에서는 최소한 다음 조건을 검증합니다.

```text
500개 WebSocket 연결 유지
2,500 deliveries/s 지속 처리
5,000 deliveries/s 짧은 burst 처리
outbound queue 지속 증가 없음
전송 오류 및 send buffer 초과 없음
```

현재 예상 지속 피크는 약 **2,500 deliveries/s, 11~20Mbps**이므로, 부하 시험을 통과한다면 단일 Spring Boot 서버의 WebSocket/STOMP 직접 연결 구조를 사용할 수 있습니다.


## 현재 상태

설계 문서를 작성 중입니다.
