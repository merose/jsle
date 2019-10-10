package org.yamcs.sle;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

import com.beanit.jasn1.ber.BerTag;
import org.yamcs.sle.Constants.ParameterName;

import ccsds.sle.transfer.service.common.types.InvokeId;
import ccsds.sle.transfer.service.raf.incoming.pdus.RafGetParameterInvocation;
import ccsds.sle.transfer.service.raf.incoming.pdus.RafStartInvocation;
import ccsds.sle.transfer.service.raf.incoming.pdus.RafUsertoProviderPdu;
import ccsds.sle.transfer.service.raf.outgoing.pdus.FrameOrNotification;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafGetParameterReturn;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafStartReturn;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafStatusReportInvocation;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafSyncNotifyInvocation;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafTransferBuffer;
import ccsds.sle.transfer.service.raf.outgoing.pdus.RafTransferDataInvocation;
import ccsds.sle.transfer.service.raf.structures.LockStatusReport;
import ccsds.sle.transfer.service.raf.structures.Notification;
import ccsds.sle.transfer.service.raf.structures.RafGetParameter;
import ccsds.sle.transfer.service.raf.structures.RafParameterName;
import ccsds.sle.transfer.service.service.instance.id.ServiceInstanceAttribute;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static org.yamcs.sle.Constants.*;

/**
 * Implementation for the CCSDS RECOMMENDED STANDARD FOR SLE RAF SERVICE
 * CCSDS 911.1-B-4 August 2016
 * https://public.ccsds.org/Pubs/911x1b4.pdf
 * 
 * @author nm
 *
 */
public class RafServiceUserHandler extends AbstractServiceUserHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Isp1Handler.class);
    int cltuId = 1;
    int eventInvocationId = 1;

    FrameConsumer consumer;

    private DeliveryMode deliveryMode;
    private RequestedFrameQuality requestedFrameQuality = RequestedFrameQuality.goodFramesOnly;

    public RafServiceUserHandler(Isp1Authentication auth, SleAttributes attr, DeliveryMode deliveryMode, FrameConsumer consumer) {
        super(auth, attr);
        this.consumer = consumer;
        setDeliveryMode(deliveryMode);
    }

    /**
     * Get the value of an RAF parameter from the provider
     * 
     * @param parameterId
     *            one of the parameters defined in {@link ParameterName}. Note that not all of them make sense for the
     *            RAF, see the table 3-11 in the standard to see which ones make sense.
     * @return
     */
    public CompletableFuture<RafGetParameter> getParameter(int parameterId) {
        CompletableFuture<RafGetParameter> cf = new CompletableFuture<>();
        channelHandlerContext.executor().execute(() -> sendGetParameter(parameterId, cf));
        return cf;
    }


    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(DeliveryMode deliveryMode) {
        checkUnbound();
        this.deliveryMode = deliveryMode;
    }


    /**
     * Request that the SLE service provider starts sending
     * 
     * @return
     */
    public CompletableFuture<Void> start(CcsdsTime startTime, CcsdsTime stopTime) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        channelHandlerContext.executor().execute(() -> sendStart(cf, startTime, stopTime));
        return cf;
    }
    public RequestedFrameQuality getRequestedFrameQuality() {
        return requestedFrameQuality;
    }

    /**
     * Set the requested frame quality. This has to be done before the service start.
     * 
     * @param requestedFrameQuality
     */
    public void setRequestedFrameQuality(RequestedFrameQuality requestedFrameQuality) {
        this.requestedFrameQuality = requestedFrameQuality;
    }

    /**
     * Add a monitor to be notified when events happen.
     * 
     * @param monitor
     */
    public void addMonitor(RafSleMonitor monitor) {
        monitors.add(monitor);
    }
    public void removeMonitor(RafSleMonitor monitor) {
        monitors.remove(monitor);
    }
    
    
    @Override
    void sendStart(CompletableFuture<Void> cf) {
        sendStart(cf, null, null);
    }

    protected void processData(BerTag berTag, InputStream is) throws IOException {
        if (berTag.equals(BerTag.CONTEXT_CLASS, BerTag.CONSTRUCTED, 8)) {
            RafTransferBuffer rafTransferBuffer = new RafTransferBuffer();
            rafTransferBuffer.decode(is, false);
            processTransferBuffer(rafTransferBuffer);
        } else if (berTag.equals(BerTag.CONTEXT_CLASS, BerTag.CONSTRUCTED, 1)) {
            RafStartReturn rafStartReturn = new RafStartReturn();
            rafStartReturn.decode(is, false);
            processStartReturn(rafStartReturn);
        } else if (berTag.equals(BerTag.CONTEXT_CLASS, BerTag.CONSTRUCTED, 9)) {
            RafStatusReportInvocation rafStatusReportInvocation = new RafStatusReportInvocation();
            rafStatusReportInvocation.decode(is, false);
            processStatusReportInvocation(rafStatusReportInvocation);
        } else if (berTag.equals(BerTag.CONTEXT_CLASS, BerTag.CONSTRUCTED, 7)) {
            RafGetParameterReturn rafGetParameterReturn = new RafGetParameterReturn();
            rafGetParameterReturn.decode(is, false);
            processGetParameterReturn(rafGetParameterReturn);
        } else {
            logger.warn("Unexpected state berTag: {} ", berTag);
            throw new IllegalStateException();
        }
    }

    private void sendGetParameter(int parameterId, CompletableFuture<RafGetParameter> cf) {
        RafUsertoProviderPdu rutp = new RafUsertoProviderPdu();

        RafGetParameterInvocation cgpi = new RafGetParameterInvocation();
        cgpi.setInvokeId(getInvokeId(cf));
        cgpi.setInvokerCredentials(getNonBindCredentials());
        cgpi.setRafParameter(new RafParameterName(parameterId));
        rutp.setRafGetParameterInvocation(cgpi);
        channelHandlerContext.writeAndFlush(rutp);
    }

    protected void sendStart(CompletableFuture<Void> cf, CcsdsTime start, CcsdsTime stop) {
        if (state != State.READY) {
            cf.completeExceptionally(new SleException("Cannot call start while in state " + state));
            return;
        }
        state = State.STARTING;
        this.startingCf = cf;

        RafUsertoProviderPdu rutp = new RafUsertoProviderPdu();

        RafStartInvocation rsi = new RafStartInvocation();
        rsi.setRequestedFrameQuality(
                new ccsds.sle.transfer.service.raf.structures.RequestedFrameQuality(requestedFrameQuality.getId()));
        rsi.setInvokeId(new InvokeId(1));
        rsi.setStartTime(getConditionalTime(start));
        rsi.setStopTime(getConditionalTime(stop));
        
        rsi.setInvokerCredentials(getNonBindCredentials());

        rutp.setRafStartInvocation(rsi);
        channelHandlerContext.writeAndFlush(rutp);
    }

   

    private void processStartReturn(RafStartReturn rafStartReturn) {
        verifyNonBindCredentials(rafStartReturn.getPerformerCredentials());
        if (state != State.STARTING) {
            peerAbort();
            return;
        }
        ccsds.sle.transfer.service.raf.outgoing.pdus.RafStartReturn.Result r = rafStartReturn.getResult();
        if (r.getNegativeResult() != null) {
            startingCf.completeExceptionally(new SleException("failed to start", r.getNegativeResult()));
            state = State.READY;
        } else {
            startingCf.complete(null);
            state = State.ACTIVE;
        }
    }

    private void processGetParameterReturn(RafGetParameterReturn rafGetParameterReturn) {
        verifyNonBindCredentials(rafGetParameterReturn.getPerformerCredentials());

        CompletableFuture<RafGetParameter> cf = getFuture(rafGetParameterReturn.getInvokeId());
        RafGetParameterReturn.Result r = rafGetParameterReturn.getResult();
        if (r.getNegativeResult() != null) {
            cf.completeExceptionally(new SleException("error getting parameter", r.getNegativeResult()));
        } else {
            cf.complete(r.getPositiveResult());
        }
    }

    private void processStatusReportInvocation(RafStatusReportInvocation rafStatusReportInvocation) {
        verifyNonBindCredentials(rafStatusReportInvocation.getInvokerCredentials());
        if (logger.isTraceEnabled()) {
            logger.trace("Received statusReport {}", rafStatusReportInvocation);
        }
        monitors.forEach(m -> ((RafSleMonitor)m).onRafStatusReport(rafStatusReportInvocation));
    }

    private void processTransferBuffer(RafTransferBuffer rafTransferBuffer) {
        for (FrameOrNotification fon : rafTransferBuffer.getFrameOrNotification()) {
            RafTransferDataInvocation rtdi = fon.getAnnotatedFrame();
            if (rtdi != null) {
                verifyNonBindCredentials(rtdi.getInvokerCredentials());
                consumer.acceptFrame(rtdi);
            }
            
            RafSyncNotifyInvocation rsi = fon.getSyncNotification();
            if (rsi != null) {
                try {
                    verifyNonBindCredentials(rsi.getInvokerCredentials());

                    Notification notif = rsi.getNotification();
                    if (notif.getLossFrameSync() != null) {
                        LockStatusReport lsr = notif.getLossFrameSync();

                        consumer.onLossFrameSync(CcsdsTime.fromSle(lsr.getTime()),
                                LockStatus.byId(lsr.getCarrierLockStatus().intValue()),
                                LockStatus.byId(lsr.getSubcarrierLockStatus().intValue()),
                                LockStatus.byId(lsr.getSymbolSyncLockStatus().intValue()));
                    } else if (notif.getProductionStatusChange() != null) {
                        consumer.onProductionStatusChange(
                                RafProductionStatus.byId(notif.getProductionStatusChange().intValue()));
                    } else if (notif.getExcessiveDataBacklog() != null) {
                        consumer.onExcessiveDataBacklog();
                    } else if (notif.getEndOfData() != null) {
                        consumer.onEndOfData();
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid notification received ", e);
                    peerAbort();
                }
            }
        }

    }

    protected ServiceInstanceAttribute getServiceFunctionalGroup() {
        return ServiceFunctionalGroup.rslFg.getServiceInstanceAttribute(attr.sfg);
    }

    protected ServiceInstanceAttribute getServiceNameIdentifier() {
        return ServiceNameId.raf.getServiceInstanceAttribute(deliveryMode, attr.sinst);
    }

    @Override
    protected ApplicationIdentifier getApplicationIdentifier() {
        return Constants.ApplicationIdentifier.rtnAllFrames;
    }

    public boolean isConnected() {
        return channelHandlerContext.channel().isOpen();
    }
}
