package de.dytanic.cloudnet.driver.template;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import de.dytanic.cloudnet.common.concurrent.ITask;
import de.dytanic.cloudnet.common.io.FileUtils;
import de.dytanic.cloudnet.driver.api.DriverAPIRequestType;
import de.dytanic.cloudnet.driver.api.DriverAPIUser;
import de.dytanic.cloudnet.driver.network.INetworkChannel;
import de.dytanic.cloudnet.driver.network.protocol.IPacket;
import de.dytanic.cloudnet.driver.network.protocol.chunk.ChunkedQueryResponse;
import de.dytanic.cloudnet.driver.serialization.ProtocolBuffer;
import de.dytanic.cloudnet.driver.service.ServiceTemplate;
import de.dytanic.cloudnet.driver.template.defaults.DefaultAsyncTemplateStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class RemoteTemplateStorage extends DefaultAsyncTemplateStorage implements DriverAPIUser {

    private final String name;
    private final Supplier<INetworkChannel> channelSupplier;

    public RemoteTemplateStorage(String name, Supplier<INetworkChannel> channelSupplier) {
        this.name = name;
        this.channelSupplier = channelSupplier;
    }

    @Override
    public INetworkChannel getNetworkChannel() {
        return this.channelSupplier.get();
    }

    @Override
    public @NotNull ITask<Boolean> deployAsync(@NotNull byte[] zipInput, @NotNull ServiceTemplate target) {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.DEPLOY_TEMPLATE_BYTE_ARRAY,
                buffer -> this.writeDefaults(buffer, target).writeArray(zipInput),
                this::readDefaultBooleanResponse
        );
    }

    @Override
    public @NotNull ITask<Boolean> deployAsync(@NotNull File directory, @NotNull ServiceTemplate target, @Nullable Predicate<File> fileFilter) {
        return null;
    }

    @Override
    public @NotNull ITask<Boolean> deployAsync(@NotNull InputStream inputStream, @NotNull ServiceTemplate target) {
        return null;
    }

    @Override
    public @NotNull ITask<Boolean> copyAsync(@NotNull ServiceTemplate template, @NotNull File directory) {
        return this.copyAsync(template, directory.toPath());
    }

    @Override
    public @NotNull ITask<Boolean> copyAsync(@NotNull ServiceTemplate template, @NotNull Path directory) {
        return this.zipTemplateAsync(template).mapThrowable(inputStream -> {
            if (inputStream == null) {
                return false;
            }

            return FileUtils.extract(inputStream, directory) != null;
        });
    }

    @Override
    public @NotNull ITask<byte[]> toZipByteArrayAsync(@NotNull ServiceTemplate template) {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.LOAD_TEMPLATE_ARRAY,
                buffer -> this.writeDefaults(buffer, template),
                packet -> this.readDefaults(packet).getBuffer().readArray()
        );
    }

    @Override
    public @NotNull ITask<InputStream> zipTemplateAsync(@NotNull ServiceTemplate template) {
        return this.executeChunkedWithTemplate(
                DriverAPIRequestType.LOAD_TEMPLATE_STREAM,
                template
        ).mapThrowable(chunkedResponse -> {
            TemplateStorageResponse response = chunkedResponse.getSession().getHeader().get("response", TemplateStorageResponse.class);
            this.throwException(response, () -> ProtocolBuffer.readAll(chunkedResponse.getInputStream()).readThrowable());
            return chunkedResponse.getInputStream();
        });
    }

    @Override
    public @NotNull ITask<Boolean> deleteAsync(@NotNull ServiceTemplate template) {
        return this.executeWithTemplate(DriverAPIRequestType.DELETE_TEMPLATE, template);
    }

    @Override
    public @NotNull ITask<Boolean> createAsync(@NotNull ServiceTemplate template) {
        return this.executeWithTemplate(DriverAPIRequestType.CREATE_TEMPLATE, template);
    }

    @Override
    public @NotNull ITask<Boolean> hasAsync(@NotNull ServiceTemplate template) {
        return this.executeWithTemplate(DriverAPIRequestType.CONTAINS_TEMPLATE, template);
    }

    @Override
    public @NotNull ITask<OutputStream> appendOutputStreamAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return null;
    }

    @Override
    public @NotNull ITask<OutputStream> newOutputStreamAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return null;
    }

    @Override
    public @NotNull ITask<Boolean> createFileAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeWithTemplate(DriverAPIRequestType.CREATE_FILE, template, path);
    }

    @Override
    public @NotNull ITask<Boolean> createDirectoryAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeWithTemplate(DriverAPIRequestType.CREATE_DIRECTORY, template, path);
    }

    @Override
    public @NotNull ITask<Boolean> hasFileAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.CONTAINS_FILE,
                buffer -> this.writeDefaults(buffer, template).writeString(path),
                this::readDefaultBooleanResponse
        );
    }

    @Override
    public @NotNull ITask<Boolean> deleteFileAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeWithTemplate(DriverAPIRequestType.DELETE_FILE, template, path);
    }

    @Override
    public @NotNull ITask<InputStream> newInputStreamAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeChunkedWithTemplate(
                DriverAPIRequestType.GET_FILE_CONTENT,
                template,
                path
        ).mapThrowable(chunkedResponse -> {
            TemplateStorageResponse response = chunkedResponse.getSession().getHeader().get("response", TemplateStorageResponse.class);
            this.throwException(response, () -> ProtocolBuffer.readAll(chunkedResponse.getInputStream()).readThrowable());
            return chunkedResponse.getInputStream();
        });
    }

    @Override
    public @NotNull ITask<FileInfo> getFileInfoAsync(@NotNull ServiceTemplate template, @NotNull String path) {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.GET_FILE_INFO,
                buffer -> this.writeDefaults(buffer, template).writeString(path),
                packet -> packet.getBuffer().readOptionalObject(FileInfo.class)
        );
    }

    @Override
    public @NotNull ITask<FileInfo[]> listFilesAsync(@NotNull ServiceTemplate template, @NotNull String dir, boolean deep) {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.LIST_FILES,
                buffer -> this.writeDefaults(buffer, template).writeString(dir).writeBoolean(deep),
                packet -> packet.getBuffer().readBoolean() ? packet.getBuffer().readObjectArray(FileInfo.class) : null
        );
    }

    @Override
    public @NotNull ITask<Collection<ServiceTemplate>> getTemplatesAsync() {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.GET_TEMPLATES,
                packet -> packet.getBuffer().readObjectCollection(ServiceTemplate.class)
        );
    }

    @Override
    public @NotNull ITask<Void> closeAsync() {
        return this.executeVoidDriverAPIMethod(
                DriverAPIRequestType.CLOSE_STORAGE,
                this::writeDefaults
        );
    }

    @Override
    public boolean shouldSyncInCluster() {
        return this.executeDriverAPIMethod(
                DriverAPIRequestType.SHOULD_SYNC_IN_CLUSTER,
                this::writeDefaults,
                this::readDefaultBooleanResponse
        ).get(5, TimeUnit.SECONDS, false);
    }

    @CanIgnoreReturnValue
    private ProtocolBuffer writeDefaults(ProtocolBuffer buffer, ServiceTemplate template) {
        return buffer.writeObject(template);
    }

    @CanIgnoreReturnValue
    private ProtocolBuffer writeDefaults(ProtocolBuffer buffer) {
        return buffer.writeString(this.name);
    }

    @CanIgnoreReturnValue
    private IPacket readDefaults(IPacket packet) throws IOException {
        TemplateStorageResponse response = packet.getBuffer().readEnumConstant(TemplateStorageResponse.class);
        this.throwException(response, packet.getBuffer());
        return packet;
    }

    private boolean readDefaultBooleanResponse(IPacket packet) throws IOException {
        TemplateStorageResponse response = packet.getBuffer().readEnumConstant(TemplateStorageResponse.class);
        this.throwException(response, packet.getBuffer());
        return response == TemplateStorageResponse.SUCCESS;
    }

    private void throwException(TemplateStorageResponse response, ProtocolBuffer buffer) throws IOException {
        this.throwException(response, buffer::readThrowable);
    }

    private void throwException(TemplateStorageResponse response, Supplier<Throwable> throwableSupplier) throws IOException {
        switch (response) {
            case EXCEPTION:
                Throwable throwable = throwableSupplier.get();
                if (throwable instanceof IOException) {
                    throw (IOException) throwable;
                } else if (throwable instanceof RuntimeException) {
                    throw (RuntimeException) throwable;
                }
                break;

            case TEMPLATE_STORAGE_NOT_FOUND:
                throw new IllegalArgumentException(String.format("TemplateStorage '%s' not found", this.name));
        }
    }

    private ITask<Boolean> executeWithTemplate(DriverAPIRequestType requestType, ServiceTemplate template) {
        return this.executeDriverAPIMethod(requestType, buffer -> this.writeDefaults(buffer, template), this::readDefaultBooleanResponse);
    }

    private ITask<Boolean> executeWithTemplate(DriverAPIRequestType requestType, ServiceTemplate template, String path) {
        return this.executeDriverAPIMethod(requestType, buffer -> this.writeDefaults(buffer, template).writeString(path), this::readDefaultBooleanResponse);
    }

    private ITask<ChunkedQueryResponse> executeChunkedWithTemplate(DriverAPIRequestType requestType, ServiceTemplate template) {
        return this.executeChunkedDriverAPIMethod(requestType, buffer -> this.writeDefaults(buffer, template));
    }

    private ITask<ChunkedQueryResponse> executeChunkedWithTemplate(DriverAPIRequestType requestType, ServiceTemplate template, String path) {
        return this.executeChunkedDriverAPIMethod(requestType, buffer -> this.writeDefaults(buffer, template).writeString(path));
    }

    @Override
    public String getName() {
        return this.name;
    }
}
