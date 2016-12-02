/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import java.beans.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.json.simple.*;

/**
 * Represents the speech activity of the <tt>Endpoint</tt>s in a
 * <tt>Conference</tt>. Identifies the dominant speaker <tt>Endpoint</tt> in the
 * <tt>Conference</tt> and maintains an ordered list of the <tt>Endpoint</tt>s
 * in the <tt>Conference</tt> sorted by recentness of speaker domination and/or
 * speech activity.
 *
 * @author Lyubomir Marinov
 */
public class ConferenceSpeechActivity
    extends PropertyChangeNotifier
    implements PropertyChangeListener
{
    /**
     * The name of the <tt>ConferenceSpeechActivity</tt> property
     * <tt>dominantEndpoint</tt> which identifies the dominant speaker in a
     * multipoint conference.
     */
    public static final String DOMINANT_ENDPOINT_PROPERTY_NAME
        = ConferenceSpeechActivity.class.getName() + ".dominantEndpoint";

    /**
     * The name of the <tt>ConferenceSpeechActivity</tt> property
     * <tt>endpoints</tt> which lists the <tt>Endpoint</tt>s
     * participating in/contributing to a <tt>Conference</tt>.
     */
    public static final String ENDPOINTS_PROPERTY_NAME
        = ConferenceSpeechActivity.class.getName() + ".endpoints";

    /**
     * The pool of threads utilized by <tt>ConferenceSpeechActivity</tt>.
     */
    private static final ExecutorService executorService
        = ExecutorUtils.newCachedThreadPool(true, "ConferenceSpeechActivity");

    /**
     * The <tt>Logger</tt> used by the <tt>ConferenceSpeechActivity</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(ConferenceSpeechActivity.class);

    /**
     * Parses an <tt>Object</tt> as a synchronization source identifier (SSRC).
     *
     * @param obj the <tt>Object</tt> to parse as an SSRC
     * @return the SSRC represented by <tt>obj</tt> or <tt>-1</tt> if
     * <tt>obj</tt> could not be parsed as an SSRC
     */
    private static long parseSSRC(Object obj)
    {
        long l;

        if (obj == null)
        {
            l = -1L;
        }
        else if (obj instanceof Number)
        {
            l = ((Number) obj).longValue();
        }
        else
        {
            String s = obj.toString();

            if (s == null)
            {
                l = -1L;
            }
            else
            {
                try
                {
                    l = Long.parseLong(s);
                }
                catch (NumberFormatException ex)
                {
                    l = -1L;
                }
            }
        }
        return l;
    }

    /**
     * Resolves a synchronization source identifier (SSRC) of a received RTP
     * stream as an <tt>Endpoint</tt> identifier (ID).
     *
     * @param jsonObject the <tt>JSONObject</tt> from which the SSRC is to be
     * read and into which the <tt>Endpoint</tt> ID is to be written
     * @param ssrcKey the key in <tt>jsonObject</tt> with which the SSRC to be
     * resolved is associated
     * @param endpointKey the key in <tt>jsonObject</tt> with which the resolved
     * <tt>Endpoint</tt> ID is to be associated
     */
    @SuppressWarnings("unchecked")
    private void resolveSSRCAsEndpoint(
            JSONObject jsonObject,
            String ssrcKey,
            String endpointKey)
    {
        long ssrc = parseSSRC(jsonObject.get(ssrcKey));

        if (ssrc != -1)
        {
            Endpoint endpoint = getEndpointByReceiveSSRC(ssrc);

            if (endpoint != null)
            {
                jsonObject.put(endpointKey, endpoint.getID());
            }
        }
    }

    /**
     * The <tt>ActiveSpeakerChangedListener</tt> which listens to
     * {@link #activeSpeakerDetector} about changes in the active/dominant
     * speaker in this multipoint conference.
     */
    private final ActiveSpeakerChangedListener activeSpeakerChangedListener
        = new ActiveSpeakerChangedListener()
                {
                    @Override
                    public void activeSpeakerChanged(long ssrc)
                    {
                        ConferenceSpeechActivity.this.activeSpeakerChanged(
                                ssrc);
                    }
                };

    /**
     * The <tt>ActiveSpeakerDetector</tt> which detects/identifies the
     * active/dominant speaker in {@link Conference}.
     */
    private ActiveSpeakerDetector activeSpeakerDetector;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #activeSpeakerDetector}. 
     */
    private final Object activeSpeakerDetectorSyncRoot = new Object();

    /**
     * The <tt>Endpoint</tt> which is the dominant speaker in
     * {@link Conference}.
     */
    private Endpoint dominantEndpoint;

    /**
     * The indicator which signals to {@link #eventDispatcher} that
     * {@link #dominantEndpoint} was changed and <tt>eventDispatcher</tt> may
     * have to fire an event.
     */
    private boolean dominantEndpointChanged = false;

    /**
     * The <tt>DominantSpeakerIdentification</tt> instance, if any, employed by
     * {@link #activeSpeakerDetector}.
     */
    private DominantSpeakerIdentification dominantSpeakerIdentification;

    /**
     * The ordered list of <tt>Endpoint</tt>s participating in
     * {@link Conference} with the dominant (speaker) <tt>Endpoint</tt> at the
     * beginning of the list i.e. the dominant speaker history.
     */
    private List<Endpoint> endpoints;

    /**
     * The unordered list of <tt>Endpoint</tt>s participating in
     * {@link Conference}.
     */
    @SuppressWarnings("unchecked")
    private List<Endpoint> conferenceEndpoints = Collections.EMPTY_LIST;

    /**
     * The indicator which signals to {@link #eventDispatcher} that the
     * <tt>endpoints</tt> set of {@link Conference} was changed and
     * <tt>eventDispatcher</tt> may have to fire an event.
     */
    private boolean endpointsChanged = false;

    /**
     * The background/daemon thread which fires <tt>PropertyChangeEvent</tt>s to
     * notify registered <tt>PropertyChangeListener</tt>s about changes of the
     * values of the <tt>dominantEndpoint</tt> and <tt>endpoints</tt> properties
     * of this instance.
     */
    private EventDispatcher eventDispatcher;

    /**
     * The time in milliseconds of the last execution of
     * {@link #eventDispatcher}.
     */
    private long eventDispatcherTime;

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to {@link Conference} in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s. 
     */
    final PropertyChangeListener propertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * The <tt>Object</tt> used to synchronize the access to the state of this
     * instance.
     */
    private final Object syncRoot = new Object();

    /**
     * The indicator for whether or not the <tt>Conference</tt> associated with
     * this <tt>ConferenceSpeechActivity</tt> has expired and activity no longer
     * needs to be monitored.
     */
    private boolean expired = false;

    /**
     * Initializes a new <tt>ConferenceSpeechActivity</tt> instance which is to
     * represent the speech activity in a specific <tt>Conference</tt>.
     */
    public ConferenceSpeechActivity()
    {
    }

    /**
     * Notifies this multipoint conference that the active/dominant speaker has
     * changed to one identified by a specific synchronization source
     * identifier/SSRC.
     * 
     * @param ssrc the synchronization source identifier/SSRC of the new
     * active/dominant speaker
     */
    private void activeSpeakerChanged(long ssrc)
    {
        if (!expired)
        {
            final Endpoint endpoint = getEndpointByReceiveSSRC(ssrc);

            if (logger.isTraceEnabled())
            {
                logger.trace(
                        "The dominant speaker in conference "
                            + endpoint.getConference().getID()
                            + " is now the SSRC " + ssrc + ".");
            }

            boolean maybeStartEventDispatcher = false;

            synchronized (syncRoot)
            {
                if (endpoint == null)
                {
                    /*
                     * We will NOT automatically elect a new dominant speaker
                     * HERE.
                     */
                    maybeStartEventDispatcher = true;
                }
                else
                {
                    Endpoint dominantEndpoint = getDominantEndpoint();

                    if (!endpoint.equals(dominantEndpoint))
                    {
                        this.dominantEndpoint = endpoint;
                        maybeStartEventDispatcher = true;
                    }
                }
                if (maybeStartEventDispatcher)
                {
                    dominantEndpointChanged = true;
                    maybeStartEventDispatcher();
                }
            }
        }
    }

    /**
     * Returns an <tt>Endpoint</tt>, which has <tt>ssrc</tt> in its channel's
     * list of received SSRCs, or <tt>null</tt> in case no such
     * <tt>Endpoint</tt> exists.
     *
     * @param ssrc the SSRC to search for.
     * @return an <tt>Endpoint</tt>, which has <tt>ssrc</tt> in its channel's
     * list of received SSRCs, or <tt>null</tt> in case no such
     * <tt>Endpoint</tt> exists.
     */
    private Endpoint getEndpointByReceiveSSRC(long ssrc)
    {
        for (Endpoint endpoint : endpoints)
        {
            for (Channel channel : endpoint.getChannels(MediaType.AUDIO))
            {
                if (channel instanceof RtpChannel)
                {
                    RtpChannel rtpChannel = (RtpChannel) channel;
                    if (rtpChannel.getStream().getRemoteSourceIDs()
                        .contains(ssrc))
                    {
                        return endpoint;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Indicates that the <tt>Conference</tt> has expired so there is no need to
     * listen for active speaker updates and clean up can take place.
     */
    public void expire() {
        synchronized (syncRoot)
        {
            this.expired = true;

            initializeEndpoints();
        }

        /*
         * The Conference has expired so there is no point to listen to
         * ActiveSpeakerDetector. Remove the activeSpeakerChangedListener
         * for the purposes of completeness, not because it is strictly
         * necessary.
         */
        ActiveSpeakerDetector activeSpeakerDetector =
            this.activeSpeakerDetector;

        if (activeSpeakerDetector != null)
        {
            activeSpeakerDetector.removeActiveSpeakerChangedListener(
                activeSpeakerChangedListener);
        }

        DominantSpeakerIdentification dominantSpeakerIdentification =
            this.dominantSpeakerIdentification;

        if (dominantSpeakerIdentification != null)
        {
            dominantSpeakerIdentification
                .removePropertyChangeListener(propertyChangeListener);
        }
    }

    /**
     * Retrieves a JSON representation of
     * {@link #dominantSpeakerIdentification} for the purposes of the REST API
     * of Videobridge.
     *
     * @return a <tt>JSONObject</tt> which represents
     * <tt>dominantSpeakerIdentification</tt> for the purposes of the REST API
     * of Videobridge
     */
    public JSONObject doGetDominantSpeakerIdentificationJSON()
    {
        DominantSpeakerIdentification dominantSpeakerIdentification
            = getDominantSpeakerIdentification();
        JSONObject jsonObject;

        if (dominantSpeakerIdentification == null)
        {
            // We do not know how to represent ActiveSpeakerDetector at the time
            // of this writing, we know how to represent
            // DominantSpeakerIdentification only.
            jsonObject = null;
        }
        else
        {
            if (expired)
            {
                jsonObject = null;
            }
            else
            {
                jsonObject = dominantSpeakerIdentification.doGetJSON();
                if (jsonObject != null)
                {
                    // Resolve the dominantSpeaker of
                    // DominantSpeakerIdentification which is a synchronization
                    // source identifier (SSRC) as an Endpoint.
                    resolveSSRCAsEndpoint(
                            jsonObject,
                            "dominantSpeaker",
                            "dominantEndpoint");

                    // Resolve the ssrc of each one of the speakers of
                    // DominantSpeakerIdentification as an Endpoint.
                    Object speakers = jsonObject.get("speakers");

                    if (speakers != null)
                    {
                        if (speakers instanceof JSONObject[])
                        {
                            for (JSONObject speaker : (JSONObject[]) speakers)
                            {
                                resolveSSRCAsEndpoint(
                                        speaker,
                                        "ssrc",
                                        "endpoint");
                            }
                        }
                        else if (speakers instanceof JSONArray)
                        {
                            for (Object speaker : (JSONArray) speakers)
                            {
                                if (speaker instanceof JSONObject)
                                {
                                    resolveSSRCAsEndpoint(
                                            (JSONObject) speaker,
                                            "ssrc",
                                            "endpoint");
                                }
                            }
                        }
                    }
                }
            }
        }
        return jsonObject;
    }

    /**
     * Notifies this <tt>ConferenceSpeechActivity</tt> that an
     * <tt>EventDispatcher</tt> has permanently stopped executing in its
     * associated background thread. If the specified <tt>EventDispatcher</tt>
     * is {@link #eventDispatcher}, this instance will note that it no longer
     * has an associated (executing) <tt>EventDispatcher</tt>.
     *
     * @param eventDispatcher the <tt>EventDispatcher</tt> which has exited
     */
    private void eventDispatcherExited(EventDispatcher eventDispatcher)
    {
        synchronized (syncRoot)
        {
            if (this.eventDispatcher == eventDispatcher)
            {
                this.eventDispatcher = eventDispatcher;
                eventDispatcherTime = 0;
            }
        }
    }

    /**
     * Gets the <tt>ActiveSpeakerDetector</tt> which detects/identifies the
     * active/dominant speaker in this <tt>Conference</tt>.
     *
     * @return the <tt>ActiveSpeakerDetector</tt> which detects/identifies the
     * active/dominant speaker in this <tt>Conference</tt>
     */
    private ActiveSpeakerDetector getActiveSpeakerDetector()
    {
        ActiveSpeakerDetector activeSpeakerDetector;
        boolean addActiveSpeakerChangedListener = false;

        synchronized (activeSpeakerDetectorSyncRoot)
        {
            activeSpeakerDetector = this.activeSpeakerDetector;
            if (activeSpeakerDetector == null)
            {
                ActiveSpeakerDetectorImpl asdi
                    = new ActiveSpeakerDetectorImpl();

                this.activeSpeakerDetector = activeSpeakerDetector = asdi;
                addActiveSpeakerChangedListener = true;

                /*
                 * Find the DominantSpeakerIdentification instance employed by
                 * activeSpeakerDetector, if possible, in order to enable
                 * additional functionality (e.g. debugging).
                 */
                ActiveSpeakerDetector impl = asdi.getImpl();

                if (impl instanceof DominantSpeakerIdentification)
                {
                    dominantSpeakerIdentification
                        = (DominantSpeakerIdentification) impl;
                }
                else
                {
                    dominantSpeakerIdentification = null;
                }
            }
        }

        /*
         * Listen to the activeSpeakerDetector about speaker switches in order
         * to track the dominant speaker in the multipoint conference. 
         */
        if (addActiveSpeakerChangedListener)
        {
            if (!expired)
            {
                activeSpeakerDetector.addActiveSpeakerChangedListener(
                        activeSpeakerChangedListener);

                DominantSpeakerIdentification dominantSpeakerIdentification
                    = this.dominantSpeakerIdentification;

                if (dominantSpeakerIdentification != null)
                {
                    dominantSpeakerIdentification.addPropertyChangeListener(
                            propertyChangeListener);
                }
            }
        }

        return activeSpeakerDetector;
    }

    /**
     * Gets the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance.
     *
     * @return the <tt>Endpoint</tt> which is the dominant speaker in the
     * multipoint conference represented by this instance or <tt>null</tt>
     */
    public Endpoint getDominantEndpoint()
    {
        Endpoint dominantEndpoint;

        synchronized (syncRoot)
        {
            if (this.dominantEndpoint == null)
            {
                dominantEndpoint = null;
            }
            else
            {
                dominantEndpoint = this.dominantEndpoint;
                if (dominantEndpoint.isExpired())
                    this.dominantEndpoint = null;
            }
        }
        return dominantEndpoint;
    }

    /**
     * Gets the <tt>DominantSpeakerIdentification</tt> instance, if any,
     * employed by {@link #activeSpeakerDetector}.
     *
     * @return the <tt>DominantSpeakerIdentification</tt> instance, if any,
     * employed by <tt>activeSpeakerDetector</tt>
     */
    private DominantSpeakerIdentification getDominantSpeakerIdentification()
    {
        // Make sure that dominantSpeakerIdentification is initialized.
        getActiveSpeakerDetector();

        return dominantSpeakerIdentification;
    }

    /**
     * Gets the ordered list of <tt>Endpoint</tt>s participating in the
     * multipoint conference represented by this instance with the dominant
     * (speaker) <tt>Endpoint</tt> at the beginning of the list i.e. the
     * dominant speaker history.
     *
     * @return the ordered list of <tt>Endpoint</tt>s participating in the
     * multipoint conference represented by this instance with the dominant
     * (speaker) <tt>Endpoint</tt> at the beginning of the list
     */
    public List<Endpoint> getEndpoints()
    {
        List<Endpoint> ret;

        synchronized (syncRoot)
        {
            /*
             * The list of Endpoints of this instance is ordered by recentness
             * of speaker domination and/or speech activity. The list of
             * Endpoints of Conference is ordered by recentness of Endpoint
             * instance initialization. The list of Endpoints of this instance
             * is initially populated with the Endpoints of the conference. 
             */
            initializeEndpoints();

            // The return value is the list of Endpoints of this instance.
            ret = Collections.unmodifiableList(endpoints);
        }

        return ret;
    }

    /**
     * Initialize the list of <tt>Endpoint</tt>s by populating it with the
     * <tt>Endpoints</tt> of the <tt>Conference</tt> or an empty list if the
     * Conference has expired.
     */
    private void initializeEndpoints() {
        if (expired)
        {
            endpoints = new ArrayList<>();
        }
        else
        {
            endpoints = new ArrayList<>(conferenceEndpoints);
        }
    }

    /**
     * Notifies this instance that a new audio level was received or measured by
     * a <tt>Channel</tt> for an RTP stream with a specific synchronization
     * source identifier/SSRC.
     *
     * @param channel the <tt>Channel</tt> which received or measured the new
     * audio level for the RTP stream identified by the specified <tt>ssrc</tt>
     * @param ssrc the synchronization source identifier/SSRC of the RTP stream
     * for which a new audio level was received or measured by the specified
     * <tt>channel</tt>
     * @param level the new audio level which was received or measured by the
     * specified <tt>channel</tt> for the RTP stream with the specified
     * <tt>ssrc</tt> 
     */
    public void levelChanged(Channel channel, long ssrc, int level)
    {
        // ActiveSpeakerDetector
        ActiveSpeakerDetector activeSpeakerDetector
            = getActiveSpeakerDetector();

        if (activeSpeakerDetector != null)
            activeSpeakerDetector.levelChanged(ssrc, level);

        // Endpoint
        Endpoint endpoint = channel.getEndpoint();

        if (endpoint != null)
            endpoint.audioLevelChanged(channel, ssrc, level);
    }

    /**
     * Starts a new <tt>EventDispatcher</tt> or notifies an existing one to fire
     * events to registered listeners about changes of the values of the
     * <tt>dominantEndpoint</tt> and <tt>endpoints</tt> properties of this
     * instance.
     */
    private void maybeStartEventDispatcher()
    {
        synchronized (syncRoot)
        {
            if (this.eventDispatcher == null)
            {
                EventDispatcher eventDispatcher = new EventDispatcher(this);
                boolean scheduled = false;

                this.eventDispatcher = eventDispatcher;
                eventDispatcherTime = 0;
                try
                {
                    executorService.execute(eventDispatcher);
                    scheduled = true;
                }
                finally
                {
                    if (!scheduled && (this.eventDispatcher == eventDispatcher))
                    {
                        this.eventDispatcher = null;
                        eventDispatcherTime = 0;
                    }
                }
            }
            else
            {
                syncRoot.notify();
            }
        }
    }

    /**
     * Notifies this instance that there was a change in the value of a property
     * of an object in which this instance is interested.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the object of
     * interest, the name of the property and the old and new values of that
     * property
     */
    @Override
    public void propertyChange(PropertyChangeEvent ev)
    {
        // Cease to execute as soon as the Conference expires.
        if (expired)
            return;

        String propertyName = ev.getPropertyName();

        if (Conference.ENDPOINTS_PROPERTY_NAME.equals(propertyName))
        {
            synchronized (syncRoot)
            {
                endpointsChanged = true;
                maybeStartEventDispatcher();
            }
        }
        else if (DominantSpeakerIdentification.DOMINANT_SPEAKER_PROPERTY_NAME
                .equals(propertyName))
        {
            DominantSpeakerIdentification dominantSpeakerIdentification
                = this.dominantSpeakerIdentification;

            if ((dominantSpeakerIdentification != null)
                    && dominantSpeakerIdentification.equals(ev.getSource()))
            {
                // TODO Auto-generated method stub
            }
        }
    }

    /**
     * Runs in the background thread of {@link #eventDispatcher} to possibly
     * fire events.
     *
     * @param eventDispatcher the <tt>EventDispatcher</tt> which is calling back
     * to this instance
     * @return <tt>true</tt> if the specified <tt>eventDispatcher</tt> is to
     * continue with its next iteration and call back to this instance again or
     * <tt>false</tt> to have the specified <tt>eventDispatcher</tt> break out
     * of its loop  and not call back to this instance again
     */
    private boolean runInEventDispatcher(EventDispatcher eventDispatcher)
    {
        boolean shouldFireEndpointsChangedEvent;
        boolean shouldFireDominantEndpointChangedEvent = false;

        synchronized (syncRoot)
        {
            /*
             * Most obviously, an EventDispatcher should cease to execute as
             * soon as this ConferenceSpeechActivity stops employing it.
             */
            if (this.eventDispatcher != eventDispatcher)
                return false;

            /*
             * As soon as the Conference associated with this instance expires,
             * kill all background threads.
             */
            if (expired)
                return false;

            long now = System.currentTimeMillis();

            if (!this.dominantEndpointChanged && !this.endpointsChanged)
            {
                long wait = 100 - (now - eventDispatcherTime);

                if (wait > 0)
                {
                    try
                    {
                        syncRoot.wait(wait);
                    }
                    catch (InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }
            }
            eventDispatcherTime = now;

            shouldFireEndpointsChangedEvent = updateEndpoints();

            /*
             * The activeSpeakerDetector decides when the dominantEndpoint
             * changes at the time of this writing.
             */
            if (this.dominantEndpointChanged)
            {
                shouldFireDominantEndpointChangedEvent = true;
                this.dominantEndpointChanged = false;
            }
        }

        if (shouldFireEndpointsChangedEvent)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        if (shouldFireDominantEndpointChangedEvent)
            firePropertyChange(DOMINANT_ENDPOINT_PROPERTY_NAME, null, null);

        return true;
    }

    /**
     * Synchronize the set of <tt>Endpoint</tt>s of this instance with the set
     * of <tt>Endpoint</tt>s of the <tt>Conference</tt> and ensure the current
     * dominant speaker is at the front.
     *
     * @return <tt>true</tt> if there were any changes to the sorted list of
     * <tt>Endpoint</tt>s
     */
    private boolean updateEndpoints()
    {
        boolean updated = false;

        if (endpoints == null)
        {
            initializeEndpoints();
            updated = true;
        }
        else
        {
            /*
             * Remove the Endpoints of this instance which are no longer in
             * the conference.
             */
            for (Iterator<Endpoint> i = endpoints.iterator(); i.hasNext();)
            {
                Endpoint endpoint = i.next();

                if (endpoint.isExpired())
                {
                    i.remove();
                    updated = true;
                }
                else if (conferenceEndpoints.contains(endpoint))
                {
                    conferenceEndpoints.remove(endpoint);
                }
                else
                {
                    i.remove();
                    updated = true;
                }
            }

            /*
             * Add the Endpoints of the conference which are not in this
             * instance yet.
             */
            if (!conferenceEndpoints.isEmpty())
            {
                for (Endpoint endpoint : conferenceEndpoints)
                {
                    endpoints.add(endpoint);
                }

                updated = true;
            }
        }

        this.endpointsChanged = false;

        /*
         * Make sure that the dominantEndpoint is at the top of the list of
         * the Endpoints of this instance.
         */
        Endpoint dominantEndpoint = getDominantEndpoint();

        if (dominantEndpoint != null)
        {
            endpoints.remove(dominantEndpoint);
            endpoints.add(0, dominantEndpoint);
        }

        return updated;
    }

    /**
     * Represents a background/daemon thread which fires events to registered
     * listeners notifying about changes in the values of the
     * <tt>dominantEndpoint</tt> and <tt>endpoints</tt> properties of a specific
     * <tt>ConferenceSpeechActivity</tt>. Because <tt>EventDispatcher</tt> runs
     * in a background/daemon <tt>Thread</tt> which is a garbage collection
     * root, it keeps a <tt>WeakReference</tt> to the specified
     * <tt>ConferenceSpeechActivity</tt> in order to not accidentally prevent
     * its garbage collection.
     */
    private static class EventDispatcher
        implements Runnable
    {
        /**
         * The <tt>ConferenceSpeechActivity</tt> which has initialized this
         * instance and on behalf of which this instance is to fire events to
         * registered listeners in the background.
         */
        private final WeakReference<ConferenceSpeechActivity> owner;

        /**
         * Initializes a new <tt>EventDispatcher</tt> instance which is to fire
         * events in the background to registered listeners on behalf of a
         * specific <tt>ConferenceSpeechActivity</tt>.
         *
         * @param owner the <tt>ConferenceSpeechActivity</tt> which is
         * initializing the new instance
         */
        public EventDispatcher(ConferenceSpeechActivity owner)
        {
            this.owner = new WeakReference<>(owner);
        }

        /**
         * Runs in a background/daemon thread and notifies registered listeners
         * about changes in the values of the <tt>dominantEndpoint</tt> and
         * <tt>endpoints</tt> properties of {@link #owner}.
         */
        @Override
        public void run()
        {
            try
            {
                do
                {
                    ConferenceSpeechActivity owner = this.owner.get();

                    if ((owner == null) || !owner.runInEventDispatcher(this))
                        break;
                }
                while (true);
            }
            finally
            {
                /*
                 * Notify the ConferenceSpeechActivity that this EventDispatcher
                 * has exited in order to allow the former to forget about the
                 * latter.
                 */
                ConferenceSpeechActivity owner = this.owner.get();

                if (owner != null)
                    owner.eventDispatcherExited(this);
            }
        }
    }
}
