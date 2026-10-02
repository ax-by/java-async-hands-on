package async.m11.p4;

/** IP 위치 조회 결과. body 는 전송 계층의 응답 본문 그대로다. (주어진 타입) */
public record GeoResult(String ip, String body) {
}
