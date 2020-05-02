package peergos.server.storage;

import peergos.server.*;
import peergos.server.sql.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;

class S3Exploration {
    public static void main(String[] a) throws Exception {
        String accessKey = a[0];
        String secretKey = a[1];
        String bucketName = a[2];
        String region = "us-east-1";
        String regionEndpoint = region + ".linodeobjects.com";
        String host = bucketName + "." + regionEndpoint;

//        TransactionStore transactions = JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands());
//        S3Config config = new S3Config("", bucketName, region, accessKey, secretKey, regionEndpoint);
//        S3BlockStorage s3 = new S3BlockStorage(config, null, BlockStoreProperties.empty(), transactions, new RAMStorage());
        byte[] payload = "Hi Linode2!".getBytes();
        Multihash content = new RAMStorage().put(null, null, null, Arrays.asList(payload), null).join().get(0);
        String s3Key = DirectS3BlockStore.hashToKey(content);// "AFYREIBF5Y4OUJXNGRCHBAR2ZMPQBSW62SZDHFNX2GA6V4J3W7I63LA4UQ"
        {
            // test copying over to reset modified time
            PresignedUrl getaUrl = S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKey, secretKey);
            get(new URI(getaUrl.base).toURL(), getaUrl.fields);
            String tempKey = s3Key + "Z";
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, s3Key, tempKey,
                    ZonedDateTime.now(), host, Collections.emptyMap(), region, accessKey, secretKey);
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
                System.out.println(res);
            }
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, tempKey, s3Key,
                    ZonedDateTime.now(), host, Collections.emptyMap(), region, accessKey, secretKey);
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
                System.out.println(res);
            }
            get(new URI(getaUrl.base).toURL(), getaUrl.fields);

            // test a delete
            PresignedUrl delUrl = S3Request.preSignDelete(tempKey, ZonedDateTime.now(), host, region, accessKey, secretKey);
            delete(new URI(delUrl.base).toURL(), delUrl.fields);
            System.out.println();
        }

        // Test a list objects GET
        S3Request.ListObjectsReply listing = S3Request.listObjects("", 10, Optional.empty(),
                ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url));

        // test an authed PUT
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("Content-Type", "application/octet-stream");
        extraHeaders.put("User-Agent", "Bond, James Bond");

        boolean useIllegalPayload = false;
        boolean hashContent = true;
        String contentHash = hashContent ? ArrayOps.bytesToHex(content.getHash()) : "UNSIGNED-PAYLOAD";
        PresignedUrl putUrl = S3Request.preSignPut(s3Key, payload.length, contentHash, false,
                ZonedDateTime.now().minusMinutes(14), host, extraHeaders, region, accessKey, secretKey);

        String res = new String(write(new URI(putUrl.base).toURL(), "PUT", putUrl.fields, useIllegalPayload ? new byte[payload.length] : payload));
        System.out.println(res);

        // Test a list objects GET continuation
        S3Request.ListObjectsReply listing2 = S3Request.listObjects("", 10, Optional.of(s3Key),
                ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url));
        if (listing2.objects.get(0).key.equals(listing.objects.get(0).key))
            throw new IllegalStateException("Incorrect listing!");

        // test an authed HEAD
        PresignedUrl headUrl = S3Request.preSignHead(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKey, secretKey);
        Map<String, List<String>> headRes = head(new URI(headUrl.base).toURL(), Collections.emptyMap());
        int size = Integer.parseInt(headRes.get("Content-Length").get(0));
        if (size != payload.length)
            throw new IllegalStateException("Incorrect size: " + size);

        // test an authed read
        PresignedUrl getUrl = S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKey, secretKey);
        byte[] authReadBytes = get(new URI(getUrl.base).toURL(), getUrl.fields);
        if (! Arrays.equals(authReadBytes, payload))
            throw new IllegalStateException("Incorrect contents: " + new String(authReadBytes));

        // test an authed read which has expired
        PresignedUrl failGetUrl = S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now().minusMinutes(11), host, region, accessKey, secretKey);
        String failReadRes = new String(get(new URI(failGetUrl.base).toURL(), failGetUrl.fields));
        System.out.println(failReadRes);

        // test a public read
        String webUrl = "https://" + bucketName + ".website-" + regionEndpoint + "/" + s3Key;
        byte[] getResult = get(new URI(webUrl).toURL(), Collections.emptyMap());
        if (! Arrays.equals(getResult, payload))
            System.out.println("Incorrect contents!");
    }

    private static byte[] write(URL target, String method, Map<String, String> headers, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod(method);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        out.write(body);
        out.flush();
        out.close();

        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IOException("HTTP " + conn.getResponseCode() + ": " + conn.getResponseMessage() + "\nbody:\n" + new String(resp.toByteArray()));
        }
    }

    private static Map<String, List<String>> head(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("HEAD");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            int resp = conn.getResponseCode();
            if (resp == 200)
                return conn.getHeaderFields();
            throw new IllegalStateException("HTTP " + resp);
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException(new String(resp.toByteArray()));
        }
    }

    private static byte[] get(PresignedUrl url) {
        try {
            return get(new URI(url.base).toURL(), url.fields);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] get(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("GET");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        }
    }

    private static void delete(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("DELETE");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            int code = conn.getResponseCode();
            if (code == 204)
                return;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException("HTTP " + code + "-" + resp.toByteArray());
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException(new String(resp.toByteArray()), e);
        }
    }
}
