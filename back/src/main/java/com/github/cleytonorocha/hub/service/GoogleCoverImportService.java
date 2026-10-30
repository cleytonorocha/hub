package com.github.cleytonorocha.hub.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Service;

/** Fetches Google Books cover images while connecting to the exact public IP that was checked. */
@Service
public class GoogleCoverImportService {
	private static final int MAX_REDIRECTS = 2;
	private static final int MAX_BYTES = ReadingCoverStorageService.MAX_FILE_BYTES;
	private static final int CONNECT_TIMEOUT_MS = 4000;
	private static final int READ_TIMEOUT_MS = 5000;
	private static final long TOTAL_TIMEOUT_MS = 15000;
	private static final java.util.concurrent.ExecutorService DNS_RESOLVER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "hub-cover-dns");
		thread.setDaemon(true);
		return thread;
	});
	private static final java.util.concurrent.ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "hub-cover-deadline");
		thread.setDaemon(true);
		return thread;
	});

	public byte[] fetch(String input) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS);
		URI current = validateUri(input);
		for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
			ensureTime(deadline);
			Response response = requestPinned(current, deadline);
			if (response.status() == 301 || response.status() == 302 || response.status() == 303 || response.status() == 307 || response.status() == 308) {
				if (redirects == MAX_REDIRECTS) throw invalid("O endereço da capa redirecionou vezes demais.");
				String location = response.headers().get("location");
				if (location == null) throw invalid("O redirecionamento da capa não informou o destino.");
				current = validateUri(current.resolve(location).toString());
				continue;
			}
			if (response.status() != 200) throw invalid("O Google Books não disponibilizou essa imagem (HTTP " + response.status() + ").");
			if (response.body().length == 0 || response.body().length > MAX_BYTES) throw invalid("A imagem precisa ter entre 1 byte e 8 MiB.");
			return response.body();
		}
		throw invalid("Não foi possível importar a imagem da capa.");
	}

	private Response requestPinned(URI uri, long deadline) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		try {
			InetAddress[] resolved = DNS_RESOLVER.submit(() -> InetAddress.getAllByName(host)).get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
			if (resolved.length == 0) throw invalid("O endereço da imagem não pôde ser resolvido.");
			IOException last = null;
			for (InetAddress address : resolved) {
				if (!isPublic(address)) throw invalid("O endereço da imagem aponta para uma rede privada ou especial.");
				try { return requestToAddress(uri, address, deadline); }
				catch (IOException error) { last = error; }
			}
			throw invalid("Não foi possível conectar ao servidor da imagem: " + (last == null ? "falha de rede" : last.getMessage()));
		} catch (TimeoutException error) {
			throw invalid("A importação da imagem excedeu o tempo limite.");
		} catch (java.util.concurrent.ExecutionException error) {
			throw invalid("Não foi possível resolver o endereço da imagem.");
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw invalid("A importação da imagem foi interrompida.");
		}
	}

	private Response requestToAddress(URI uri, InetAddress address, long deadline) throws IOException {
		try (Socket raw = new Socket()) {
			raw.connect(new InetSocketAddress(address, 443), Math.min(CONNECT_TIMEOUT_MS, remainingMillis(deadline)));
			raw.setSoTimeout(READ_TIMEOUT_MS);
			var timeout = DEADLINES.schedule(() -> { try { raw.close(); } catch (IOException ignored) { } }, remainingMillis(deadline), TimeUnit.MILLISECONDS);
			try {
			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			try (SSLSocket tls = (SSLSocket) factory.createSocket(raw, uri.getHost(), 443, true)) {
				var parameters = tls.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
				tls.setSSLParameters(parameters);
				tls.startHandshake();
				String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
				if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
				var output = tls.getOutputStream();
				output.write(("GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nAccept: image/jpeg,image/png,image/*;q=0.8\r\nAccept-Encoding: identity\r\nConnection: close\r\nUser-Agent: HubCoverImporter/1.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				output.flush();
				InputStream bounded = new java.io.FilterInputStream(tls.getInputStream()) {
					private void limit() throws IOException { tls.setSoTimeout(remainingMillis(deadline)); }
					@Override public int read() throws IOException { limit(); return super.read(); }
					@Override public int read(byte[] buffer, int offset, int length) throws IOException { limit(); return super.read(buffer, offset, length); }
				};
				BufferedInputStream input = new BufferedInputStream(bounded);
				String statusLine = line(input);
				if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw invalid("O servidor retornou uma resposta inválida.");
				int status = Integer.parseInt(statusLine.substring(9, 12));
				var headers = new java.util.LinkedHashMap<String, String>();
				for (int i = 0; i < 100; i++) {
					String header = line(input);
					if (header == null) throw invalid("A resposta do servidor foi interrompida.");
					if (header.isEmpty()) break;
					int separator = header.indexOf(':');
					if (separator <= 0) throw invalid("O servidor retornou um cabeçalho inválido.");
					headers.putIfAbsent(header.substring(0, separator).trim().toLowerCase(Locale.ROOT), header.substring(separator + 1).trim());
					if (i == 99) throw invalid("O servidor retornou cabeçalhos demais.");
				}
				if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) return new Response(status, headers, new byte[0]);
				String length = headers.get("content-length");
				if (length != null) {
					long count = Long.parseLong(length);
					if (count < 1 || count > MAX_BYTES) throw invalid("A imagem pode ter até 8 MiB.");
				}
				byte[] body = "chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))
					? readChunked(input) : readBody(input, length == null ? -1 : Long.parseLong(length));
				return new Response(status, headers, body);
			}
			} finally { timeout.cancel(false); }
		}
	}

	private byte[] readBody(InputStream input, long declaredLength) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		if (declaredLength >= 0) {
			byte[] body = input.readNBytes((int) declaredLength);
			if (body.length != declaredLength) throw invalid("O download da imagem ficou incompleto.");
			return body;
		}
		byte[] buffer = new byte[8192];
		while (true) {
			int limit = (int) Math.min(buffer.length, MAX_BYTES + 1L - bytes.size());
			if (limit <= 0) throw invalid("A imagem pode ter até 8 MiB.");
			int count = input.read(buffer, 0, limit);
			if (count < 0) break;
			bytes.write(buffer, 0, count);
		}
		return bytes.toByteArray();
	}

	private byte[] readChunked(InputStream input) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (true) {
			String chunk = line(input);
			if (chunk == null) throw invalid("O download da imagem ficou incompleto.");
			int extension = chunk.indexOf(';');
			long size;
			try { size = Long.parseLong(extension < 0 ? chunk.trim() : chunk.substring(0, extension).trim(), 16); }
			catch (NumberFormatException error) { throw invalid("O servidor retornou uma imagem inválida."); }
			if (size == 0) { while (true) { String trailer = line(input); if (trailer == null || trailer.isEmpty()) break; } return bytes.toByteArray(); }
			if (size < 0 || size > MAX_BYTES - bytes.size()) throw invalid("A imagem pode ter até 8 MiB.");
			byte[] chunkBytes = input.readNBytes((int) size);
			if (chunkBytes.length != size || input.read() != '\r' || input.read() != '\n') throw invalid("O download da imagem ficou incompleto.");
			bytes.write(chunkBytes);
		}
	}

	private String line(InputStream input) throws IOException {
		ByteArrayOutputStream value = new ByteArrayOutputStream();
		int current;
		while ((current = input.read()) >= 0) {
			if (current == '\n') {
				byte[] bytes = value.toByteArray();
				int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
				return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
			}
			if (value.size() > 8192) throw invalid("A resposta do servidor excede o limite.");
			value.write(current);
		}
		return value.size() == 0 ? null : value.toString(StandardCharsets.ISO_8859_1);
	}

	private URI validateUri(String input) {
		try {
			URI uri = URI.create(input).normalize();
			String host = uri.getHost();
			if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443) || !allowedHost(host) || uri.getRawFragment() != null)
				throw invalid("Use HTTPS em books.google.com, *.googleusercontent.com ou *.gstatic.com.");
			return uri;
		} catch (IllegalArgumentException error) {
			if (error instanceof ImportUrlException) throw error;
			throw invalid("O link da imagem é inválido.");
		}
	}

	private boolean allowedHost(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		return lower.equals("books.google.com") || lower.endsWith(".googleusercontent.com") || lower.endsWith(".gstatic.com");
	}

	private boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			int a = b[0] & 255, c = b[1] & 255;
			return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c >= 64 && c <= 127)
				|| (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31) || (a == 192 && (c == 0 || c == 168))
				|| (a == 198 && (c == 18 || c == 19 || c == 51)) || (a == 203 && c == 0));
		}
		if (address instanceof Inet6Address) {
			boolean ipv4Mapped = true;
			for (int i = 0; i < 10; i++) if (b[i] != 0) { ipv4Mapped = false; break; }
			if (ipv4Mapped && (b[10] & 255) == 0xff && (b[11] & 255) == 0xff) return false;
			int first = b[0] & 255;
			int second = b[1] & 255;
			boolean globalUnicast = (first & 0xe0) == 0x20;
			boolean special2001 = first == 0x20 && second == 0x01 && ((b[2] & 255) <= 1
				|| ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8));
			if (!globalUnicast || special2001 || (first == 0x20 && second == 0x02)) return false;
		}
		return true;
	}

	private int remainingMillis(long deadline) { long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()); if (remaining <= 0) throw invalid("A importação da imagem excedeu o tempo limite."); return (int) Math.min(Integer.MAX_VALUE, remaining); }
	private void ensureTime(long deadline) { remainingMillis(deadline); }
	private ImportUrlException invalid(String message) { return new ImportUrlException(message); }
	private record Response(int status, java.util.Map<String, String> headers, byte[] body) {}
	private static class ImportUrlException extends IllegalArgumentException { ImportUrlException(String message) { super(message); } }
}
