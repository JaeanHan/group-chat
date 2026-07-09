# Group Chat

오픈 채팅방 서버를 설계하고 구현하기 위한 저장소입니다.

현재 문서는 프로덕션에서 재사용 가능한 공통 채팅방 모듈을 목표로, 도메인 상태, command/query 계약, 실시간 이벤트 전파, outbox 전달 보장을 정리합니다.

## 문서 읽는 순서

1. [Backend Core](./chat-server/docs/core.md)
2. [Backend Domain Model](./chat-server/docs/domain-model.md)
3. [Backend Commands](./chat-server/docs/commands.md)
4. [Backend Queries](./chat-server/docs/queries.md)
5. [Backend Events](./chat-server/docs/events.md)
6. [Backend Outbox](./chat-server/docs/outbox.md)

## 백엔드 설계 문서

- [core.md](./chat-server/docs/core.md)  
  오픈 채팅방의 핵심 문제와 설계 원칙을 설명합니다.

- [domain-model.md](./chat-server/docs/domain-model.md)  
  채팅방, 참여, 메시지, 읽음 상태, 실시간 연결의 도메인 모델을 정의합니다.

- [commands.md](./chat-server/docs/commands.md)  
  상태를 변경하는 command의 사전 조건, 상태 변화, 이벤트 생성 기준을 정의합니다.

- [queries.md](./chat-server/docs/queries.md)  
  상태를 변경하지 않는 조회의 권한과 조회 기준을 정의합니다.

- [events.md](./chat-server/docs/events.md)  
  서버에서 클라이언트로 전파하는 WebSocket 이벤트의 payload와 적용 기준을 정의합니다.

- [outbox.md](./chat-server/docs/outbox.md)  
  DB에 확정된 변경을 WebSocket으로 안정적으로 전달하기 위한 Outbox relay 정책을 정의합니다.

## 현재 상태

설계 문서를 작성 중입니다.
