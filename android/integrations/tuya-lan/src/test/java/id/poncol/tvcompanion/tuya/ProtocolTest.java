package id.poncol.tvcompanion.tuya;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
public class ProtocolTest {
 static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
 static final byte[] KEY="0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
 static final byte[] RESPONSE=hex("0000669900000000000800000010000000496162636465666768696a6b6ce5d27cb528873dd52a128e41d40cef2b30619e925dd71160b5def75a253f21c5793c73b30c81f372b6c53c2252c46b98db880ec3767e048182b114e39500009966");
 @Test public void requestMatchesTinyTuya() throws Exception {
  assertArrayEquals(hex("00006699000000000007000000100000001e6162636465666768696a6b6c9eaf2bf09c6673b0087216746b9258e3a14f00009966"),Tuya35.frame(7,16,"{}".getBytes(StandardCharsets.US_ASCII),KEY,"abcdefghijkl".getBytes(StandardCharsets.US_ASCII)));
 }
 @Test public void sessionMatchesTinyTuya() throws Exception {
  assertArrayEquals(hex("f4df71bc5aa854a4583db933ff33d218"),Tuya35.sessionKey(KEY,"abcdefghijklmnop".getBytes(StandardCharsets.US_ASCII),"ponmlkjihgfedcba".getBytes(StandardCharsets.US_ASCII)));
 }
 @Test public void responseAuthenticates() throws Exception {
  byte[] data=Tuya35.decode(Arrays.copyOf(RESPONSE,18),Arrays.copyOfRange(RESPONSE,18,RESPONSE.length),KEY);
  assertEquals("{\"dps\":{\"20\":true,\"21\":\"white\",\"22\":304}}",new String(data,4,data.length-4,StandardCharsets.UTF_8));
 }
 @Test public void corruptTagRejected() throws Exception {
  byte[] bad=RESPONSE.clone();bad[35]^=1;
  try {Tuya35.decode(Arrays.copyOf(bad,18),Arrays.copyOfRange(bad,18,bad.length),KEY);fail();}catch(javax.crypto.AEADBadTagException expected){}
 }
 @Test public void headerTamperingRejected() throws Exception {
  byte[] bad=RESPONSE.clone();bad[9]^=1;
  try {Tuya35.decode(Arrays.copyOf(bad,18),Arrays.copyOfRange(bad,18,bad.length),KEY);fail();}catch(javax.crypto.AEADBadTagException expected){}
 }
 @Test public void wrongKeyRejected() throws Exception {
  try {Tuya35.decode(Arrays.copyOf(RESPONSE,18),Arrays.copyOfRange(RESPONSE,18,RESPONSE.length),new byte[16]);fail();}catch(javax.crypto.AEADBadTagException expected){}
 }
 @Test public void badSuffixRejected() throws Exception {
  byte[] bad=RESPONSE.clone();bad[bad.length-1]^=1;
  try{Tuya35.decode(Arrays.copyOf(bad,18),Arrays.copyOfRange(bad,18,bad.length),KEY);fail();}catch(java.io.IOException expected){}
 }
 @Test public void privateAddressesOnly() throws Exception {
  for(String ip:new String[]{"10.0.0.1","172.16.0.1","192.168.1.1"})assertTrue(Tuya35.address(ip).isSiteLocalAddress());
  for(String ip:new String[]{"example.com","127.0.0.1","8.8.8.8","169.254.1.1","192.168.1.256","0.0.0.0"}){
   try{Tuya35.address(ip);fail(ip);}catch(java.io.IOException expected){}
  }
 }
}
