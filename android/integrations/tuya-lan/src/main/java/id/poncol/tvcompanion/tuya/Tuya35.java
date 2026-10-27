package id.poncol.tvcompanion.tuya;

import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Single-lamp Tuya 3.5 LAN client. No cloud, discovery, or automatic write retries. */
final class Tuya35 implements AutoCloseable {
    private final Socket socket = new Socket();
    private byte[] key;
    private int sequence = 1;
    private final long deadline = System.nanoTime() + 12_000_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
    static byte[] crypt(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(data);
    }
    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data);
    }
    static byte[] sessionKey(byte[] key, byte[] local, byte[] remote) throws Exception {
        byte[] xor = new byte[16]; for (int i=0;i<16;i++) xor[i]=(byte)(local[i]^remote[i]);
        return Arrays.copyOf(crypt(Cipher.ENCRYPT_MODE,key,Arrays.copyOf(local,12),null,xor),16);
    }
    static byte[] frame(int seq, int cmd, byte[] payload, byte[] key, byte[] iv) throws Exception {
        byte[] header = ByteBuffer.allocate(18).putInt(0x6699).putShort((short)0).putInt(seq).putInt(cmd).putInt(payload.length+28).array();
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE,key,iv,Arrays.copyOfRange(header,4,18),payload);
        return ByteBuffer.allocate(18+12+encrypted.length+4).put(header).put(iv).put(encrypted).putInt(0x9966).array();
    }
    static byte[] decode(byte[] header, byte[] body, byte[] key) throws Exception {
        ByteBuffer h = ByteBuffer.wrap(header);
        if (h.getInt()!=0x6699 || h.getShort()!=0) throw new IOException("Invalid frame");
        int length = ByteBuffer.wrap(header).getInt(14);
        if (length<28 || length>65536 || body.length!=length+4 || ByteBuffer.wrap(body,body.length-4,4).getInt()!=0x9966)
            throw new IOException("Invalid frame length");
        return crypt(Cipher.DECRYPT_MODE,key,Arrays.copyOf(body,12),Arrays.copyOfRange(header,4,18),Arrays.copyOfRange(body,12,body.length-4));
    }
    static InetAddress address(String ip) throws Exception {
        String[] parts=ip.split("\\.",-1); if(parts.length!=4) throw new IOException("Use lamp LAN IPv4");
        byte[] bytes=new byte[4];
        for(int i=0;i<4;i++){ if(!parts[i].matches("[0-9]{1,3}"))throw new IOException("Invalid address"); int n=Integer.parseInt(parts[i]); if(n>255)throw new IOException("Invalid address"); bytes[i]=(byte)n; }
        int a=bytes[0]&255,b=bytes[1]&255;
        if (!(a==10 || a==172 && b>=16 && b<=31 || a==192 && b==168)) throw new IOException("Use a private LAN address");
        return InetAddress.getByAddress(bytes);
    }
    Tuya35(String ip, byte[] localKey) throws Exception {
        if(localKey.length!=16)throw new IOException("Invalid local key");
        key=localKey.clone();
        try {
            socket.connect(new InetSocketAddress(address(ip),6668),4000);
            socket.setSoTimeout(4000); socket.setTcpNoDelay(true);
            byte[] local=random(16); send(3,local);
            Packet response=receive();
            if(response.cmd!=4 || response.payload.length!=48)throw new IOException("Negotiation failed");
            byte[] remote=Arrays.copyOf(response.payload,16);
            if(!MessageDigest.isEqual(hmac(key,local),Arrays.copyOfRange(response.payload,16,48)))throw new IOException("Authentication failed");
            send(5,hmac(key,remote));
            byte[] session=sessionKey(key,local,remote); Arrays.fill(key,(byte)0); key=session;
        } catch(Exception e){close();throw e;}
    }
    private void send(int cmd,byte[] data) throws Exception {
        if(System.nanoTime()>deadline)throw new IOException("Request deadline");
        socket.getOutputStream().write(frame(sequence++,cmd,data,key,random(12)));
    }
    private static final class Packet {
        final int cmd; final byte[] payload;
        Packet(int cmd,byte[] payload){this.cmd=cmd;this.payload=payload;}
    }
    private Packet receive() throws Exception {
        long left=(deadline-System.nanoTime())/1_000_000;
        if(left<=0)throw new IOException("Request deadline");
        socket.setSoTimeout((int)Math.min(4000,left));
        DataInputStream in=new DataInputStream(socket.getInputStream());
        byte[] header=new byte[18]; in.readFully(header);
        int length=ByteBuffer.wrap(header).getInt(14);
        if(length<28 || length>65536)throw new IOException("Invalid frame size");
        byte[] body=new byte[length+4];in.readFully(body);
        byte[] payload=decode(header,body,key);
        if(payload.length<4 || ByteBuffer.wrap(payload).getInt()!=0)throw new IOException("Lamp rejected request");
        return new Packet(ByteBuffer.wrap(header).getInt(10),Arrays.copyOfRange(payload,4,payload.length));
    }
    JSONObject status() throws Exception {
        send(16,"{}".getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<12;i++) {
            Packet p=receive(); byte[] data=p.payload;
            if(p.cmd!=8 && p.cmd!=16)continue;
            if(data.length>=15 && data[0]=='3' && data[1]=='.')data=Arrays.copyOfRange(data,15,data.length);
            if(data.length==0)continue;
            JSONObject root=new JSONObject(new String(data,StandardCharsets.UTF_8));
            JSONObject dps=root.optJSONObject("dps");
            if(dps==null && root.optJSONObject("data")!=null)dps=root.getJSONObject("data").optJSONObject("dps");
            if(dps!=null && dps.opt("20") instanceof Boolean)return dps;
        }
        throw new IOException("No power status returned");
    }
    void write(String dp,Object value) throws Exception {
        if(!dp.equals("20") && !dp.equals("22"))throw new IOException("Unsupported control");
        if(dp.equals("20") && !(value instanceof Boolean))throw new IOException("Invalid power");
        if(dp.equals("22") && (!(value instanceof Integer) || (int)value<10 || (int)value>1000))throw new IOException("Invalid brightness");
        JSONObject root=new JSONObject().put("protocol",5).put("t",System.currentTimeMillis()/1000)
            .put("data",new JSONObject().put("dps",new JSONObject().put(dp,value)));
        byte[] json=root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload=new byte[15+json.length];System.arraycopy("3.5".getBytes(StandardCharsets.US_ASCII),0,payload,0,3);System.arraycopy(json,0,payload,15,json.length);
        send(13,payload);
    }
    @Override public void close(){try{socket.close();}catch(IOException ignored){}if(key!=null)Arrays.fill(key,(byte)0);}
}
