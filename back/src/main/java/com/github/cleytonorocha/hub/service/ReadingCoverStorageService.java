package com.github.cleytonorocha.hub.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReadingCoverStorageService {
	public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
	private static final int MAX_IMAGE_PIXELS = 20_000_000;
	private static final int MAX_IMAGE_DIMENSION = 12_000;
	private static final int MAX_ORIGINAL_EDGE = 1600;
	private static final int MAX_THUMBNAIL_EDGE = 420;
	private final Path root;

	public ReadingCoverStorageService(@Value("${hub.media.root}") String root) {
		this.root = Path.of(root).toAbsolutePath().normalize().resolve("reading-library").resolve("covers").normalize();
	}

	public StoredCover store(MultipartFile upload) {
		if (upload == null || upload.isEmpty()) throw invalid("Escolha uma imagem JPEG ou PNG.");
		if (upload.getSize() > MAX_FILE_BYTES) throw invalid("A imagem pode ter até 8 MiB.");
		try {
			return store(upload.getBytes());
		} catch (IOException error) {
			throw new CoverStorageException("Não foi possível ler a imagem enviada.", error);
		}
	}

	public StoredCover store(byte[] bytes) {
		if (bytes == null || bytes.length == 0) throw invalid("Escolha uma imagem JPEG ou PNG.");
		if (bytes.length > MAX_FILE_BYTES) throw invalid("A imagem pode ter até 8 MiB.");
		DecodedImage decoded = decode(bytes);
		BufferedImage original = resize(decoded.image(), MAX_ORIGINAL_EDGE);
		BufferedImage thumbnail = resize(decoded.image(), MAX_THUMBNAIL_EDGE);
		byte[] normalized = encode(original, decoded.format());
		byte[] thumb = encode(thumbnail, decoded.format());
		String name = UUID.randomUUID() + "." + (decoded.format().equals("png") ? "png" : "jpg");
		return write(name, normalized, thumb);
	}

	public StoredCover storePortable(byte[] bytes, String expectedFormat) {
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) throw invalid("Uma imagem da cópia excede 8 MiB.");
		DecodedImage decoded = decode(bytes);
		String format = decoded.format().equals("png") ? "png" : "jpg";
		if (!format.equals(expectedFormat) || Math.max(decoded.image().getWidth(), decoded.image().getHeight()) > MAX_ORIGINAL_EDGE) {
			throw invalid("Uma imagem do pacote não está no formato de capa esperado.");
		}
		String name = UUID.randomUUID() + "." + format;
		return write(name, bytes, encode(resize(decoded.image(), MAX_THUMBNAIL_EDGE), decoded.format()));
	}

	public String validate(byte[] bytes) {
		DecodedImage decoded = decode(bytes);
		return decoded.format().equals("png") ? "png" : "jpg";
	}

	public byte[] read(String name, boolean thumbnail) {
		try {
			Path path = thumbnail ? thumbnailPathFor(name) : pathFor(name);
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) throw new CoverNotFoundException();
			return Files.readAllBytes(path);
		} catch (IOException error) {
			throw new CoverStorageException("Não foi possível carregar a imagem.", error);
		}
	}

	public MediaType mediaType(String name) {
		return name != null && name.toLowerCase(Locale.ROOT).endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
	}

	public long size(String name) throws IOException {
		Path path = pathFor(name);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new CoverNotFoundException();
		long size = Files.size(path);
		if (size < 1 || size > MAX_FILE_BYTES) throw new CoverNotFoundException();
		return size;
	}

	public void delete(String name) {
		if (name == null) return;
		try {
			Files.deleteIfExists(pathFor(name));
			Files.deleteIfExists(thumbnailPathFor(name));
		} catch (IOException error) {
			throw new CoverStorageException("Não foi possível remover a imagem substituída.", error);
		}
	}

	private DecodedImage decode(byte[] bytes) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) throw invalid("O arquivo não é uma imagem JPEG ou PNG válida.");
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw invalid("Use uma imagem JPEG ou PNG válida.");
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				String format = reader.getFormatName().toLowerCase(Locale.ROOT);
				if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) throw invalid("Use somente imagens JPEG ou PNG.");
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
					|| (long) width * height > MAX_IMAGE_PIXELS) throw invalid("A imagem excede as dimensões permitidas para uma capa.");
				BufferedImage image = reader.read(0);
				if (image == null) throw invalid("O arquivo de imagem está corrompido.");
				return new DecodedImage(image, format.equals("png") ? "png" : "jpeg");
			} finally {
				reader.dispose();
			}
		} catch (CoverValidationException error) {
			throw error;
		} catch (IOException | RuntimeException error) {
			throw new CoverValidationException("O arquivo de imagem está corrompido ou não é compatível.", error);
		}
	}

	private BufferedImage resize(BufferedImage source, int maxEdge) {
		double scale = Math.min(1.0, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		BufferedImage result = new BufferedImage(width, height, type);
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		return result;
	}

	private byte[] encode(BufferedImage image, String format) {
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, format, output)) throw new IOException("Formato de imagem sem codificador.");
			return output.toByteArray();
		} catch (IOException error) {
			throw new CoverStorageException("Não foi possível preparar a imagem.", error);
		}
	}

	private StoredCover write(String name, byte[] image, byte[] thumbnail) {
		try {
			Files.createDirectories(root);
			writeAtomically(pathFor(name), image);
			writeAtomically(thumbnailPathFor(name), thumbnail);
			return new StoredCover(name, mediaType(name), sha256(image));
		} catch (IOException error) {
			delete(name);
			throw new CoverStorageException("Não foi possível armazenar a imagem.", error);
		}
	}

	private String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponível.", impossible); }
	}

	private void writeAtomically(Path target, byte[] bytes) throws IOException {
		Path temporary = Files.createTempFile(root, ".cover-", ".tmp");
		try {
			Files.write(temporary, bytes);
			try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target); }
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private Path pathFor(String name) {
		if (name == null || !name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")) throw new CoverNotFoundException();
		Path path = root.resolve(name).normalize();
		if (!path.startsWith(root)) throw new CoverNotFoundException();
		return path;
	}

	private Path thumbnailPathFor(String name) {
		Path source = pathFor(name);
		return root.resolve(source.getFileName().toString().replaceFirst("\\.(jpg|png)$", ".thumb.$1")).normalize();
	}

	private static CoverValidationException invalid(String message) { return new CoverValidationException(message); }

	public record StoredCover(String name, MediaType mediaType, String sha256) {}
	private record DecodedImage(BufferedImage image, String format) {}
	public static class CoverValidationException extends RuntimeException {
		public CoverValidationException(String message) { super(message); }
		public CoverValidationException(String message, Throwable cause) { super(message, cause); }
	}
	public static class CoverStorageException extends RuntimeException {
		public CoverStorageException(String message, Throwable cause) { super(message, cause); }
	}
	public static class CoverNotFoundException extends RuntimeException {}
}
