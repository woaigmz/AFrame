package com.woaiqw.base.network;

import com.woaiqw.base.core.ThreadPool;
import com.woaiqw.base.network.constants.HConstants;
import com.woaiqw.base.network.core.RequestCtx;
import com.woaiqw.base.network.internel.Callback;
import com.woaiqw.base.network.internel.HAdapter;
import com.woaiqw.base.network.internel.Parser;
import com.woaiqw.base.network.utils.L;
import com.woaiqw.base.network.utils.OkHttpHelper;
import com.woaiqw.base.network.utils.ParamsUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;


/**
 * Created by haoran on 2019/6/4.
 */
public class OkHttpAdapter implements HAdapter {

    private OkHttpClient clone;
    private CompositeDisposable disposable;
    private Disposable dispatcher;

    public OkHttpAdapter() {
        this.clone = OkHttpHelper.getInstance().getClient();
        disposable = new CompositeDisposable();
    }

    @Override
    public void request(final RequestCtx ctx, final Parser parser, final Callback callback) {
        if (ctx == null) {
            throw new RuntimeException(" please new ctx ");
        }
        if (parser == null) {
            throw new RuntimeException(" parser is empty ");
        }
        if (callback == null) {
            throw new RuntimeException(" callback is empty ");
        }
        if (ctx.getUrl() == null || ctx.getUrl().isEmpty()) {
            throw new RuntimeException(" url is empty ");
        }
        ThreadPool.getInstance().getPool().execute(new Runnable() {
            @Override
            public void run() {
                Call call = generateCall(ctx);
                doRequest(call, parser, callback);
            }
        });

    }

    private void doRequest(Call call, final Parser parser, final Callback callback) {

        if (call.isCanceled()) {
            dispatcher(parser, callback, true, null, null);
            return;
        }
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                dispatcher(parser, callback, false, null, e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                ResponseBody rawBody = response.body();
                if (rawBody == null) {
                    dispatcher(parser, callback, false, null, new Exception(" response body == null "));
                    return;
                }
                response = response.newBuilder()
                        .body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength()))
                        .build();

                int code = response.code();
                if (code < 200 || code >= 300) {
                    dispatcher(parser, callback, false, null, new Exception(" response.code < 200 || response.code > 300 "));
                    rawBody.close();
                }
                if (code == 204 || code == 205) {
                    dispatcher(parser, callback, true, null, null);
                    rawBody.close();
                }
                L.e("threadName - response", Thread.currentThread().getName());
                dispatcher(parser, callback, true, rawBody, null);
            }
        });

    }

    private void dispatcher(final Parser parser, final Callback callback,
                            final boolean success, final ResponseBody rawBody, final Throwable error) {
        dispatcher = Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(final ObservableEmitter<String> emitter) throws IOException {
                L.e("threadName - dispatcher", Thread.currentThread().getName());
                if (success) {
                    ExceptionCatchingRequestBody catchingBody = new ExceptionCatchingRequestBody(rawBody);
                    catchingBody.throwIfCaught();
                    String string = catchingBody.string();
                    emitter.onNext(string);
                } else {
                    emitter.onError(error);
                }

            }
        }).subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .map(new Function<String, Object>() {
                    @Override
                    public Object apply(String s) throws Exception {
                        L.e("threadName - parse", Thread.currentThread().getName());
                        return parser.parse(s);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<Object>() {
                    @Override
                    public void accept(Object data) {
                        L.e("threadName - then", Thread.currentThread().getName());
                        callback.then(data);
                        if (disposable != null) {
                            disposable.remove(dispatcher);
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        callback.error(throwable.toString());
                        if (disposable != null) {
                            disposable.remove(dispatcher);
                        }
                    }
                });
        disposable.add(dispatcher);
    }


    private Call generateCall(RequestCtx ctx) {

        String baseUrl = ctx.getUrl();

        if (ctx.getParamMap() != null && !ctx.getParamMap().isEmpty()) {
            baseUrl = ParamsUtils.getUrl(ctx.getUrl(), ctx.getParamMap());
        }

        Request.Builder builder = new Request.Builder()
                .url(baseUrl);

        if (ctx.getMethod() != null && !ctx.getMethod().isEmpty()) {
            switch (ctx.getMethod()) {
                case HConstants.get:
                    break;
                case HConstants.post:
                    builder.post(RequestBody.create(MediaType.parse(HConstants.JSON), ctx.getBody()));
                    break;
            }
        }

        HashMap<String, String> headerMap = ctx.getHeaderMap();
        if (headerMap != null && !headerMap.isEmpty()) {
            for (Map.Entry<String, String> entry : headerMap.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        Call call = clone.newCall(builder.build());

        if (ctx.isCanceled()) {
            call.cancel();
        }

        return call;

    }

    static final class NoContentResponseBody extends ResponseBody {
        private final MediaType contentType;
        private final long contentLength;

        NoContentResponseBody(MediaType contentType, long contentLength) {
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public BufferedSource source() {
            throw new IllegalStateException("Cannot read raw response body of a converted body.");
        }
    }

    static final class ExceptionCatchingRequestBody extends ResponseBody {
        private final ResponseBody delegate;
        IOException thrownException;

        ExceptionCatchingRequestBody(ResponseBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            return Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    try {
                        return super.read(sink, byteCount);
                    } catch (IOException e) {
                        thrownException = e;
                        throw e;
                    }
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }

        void throwIfCaught() throws IOException {
            if (thrownException != null) {
                throw thrownException;
            }
        }
    }

}
