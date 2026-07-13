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

## 현재 상태

설계 문서를 작성 중입니다.
