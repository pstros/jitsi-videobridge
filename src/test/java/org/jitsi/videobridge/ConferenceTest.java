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
import java.util.*;

import org.easymock.*;
import org.jitsi.service.neomedia.*;
import org.junit.*;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

/**
 * Tests the Conference class at the unit level
 */
public class ConferenceTest
    extends EasyMockSupport
{
    private Conference conference;

    private Videobridge videobridgeMock;

    private ConferenceSpeechActivity speechActivityMock;

    private Content contentMock;

    private RtpChannel channelMock;

    private Endpoint endpointMock;

    private List<Endpoint> endpoints;

    @Before
    public void setUp()
    {
        videobridgeMock = mock(Videobridge.class);
        speechActivityMock = niceMock(ConferenceSpeechActivity.class);
        contentMock = mock(Content.class);
        channelMock = mock(RtpChannel.class);
        endpointMock = mock(Endpoint.class);

        endpoints = new ArrayList<>(Arrays.asList(endpointMock));

        conference =
            new Conference(videobridgeMock, speechActivityMock, "fakeId",
                "focus", "name", false);

        resetAll();
    }

    /**
     * Tests the <tt>propertyChange</tt> method of <tt>Conference</tt> when an
     * event signaling that the dominant speaker has changed is received.
     *
     * @throws Exception
     */
    @Test
    public void propertyChange_dominantSpeakerChange_broadcastUpdate()
        throws Exception
    {
        final Capture<String> messageArgument = newCapture();

        expectDominantSpeakerChanged(messageArgument);
        expectSpeechActivityEndpointsChanged();

        replayAll();

        conference.setContents(new ArrayList<>(Arrays.asList(contentMock)));
        conference.propertyChange(new PropertyChangeEvent(speechActivityMock,
            ConferenceSpeechActivity.DOMINANT_ENDPOINT_PROPERTY_NAME, null,
            null));

        verifyAll();

        assertTrue(messageArgument.getValue()
            .contains("DominantSpeakerEndpointChangeEvent"));
    }

    /**
     * Tests the <tt>propertyChange</tt> method of <tt>Conference</tt> when an
     * event signaling that the participating endpoints have changed is
     * received.
     */
    @Test
    public void propertyChange_endpointsChanged_broadcastUpdate()
    {
        expectSpeechActivityEndpointsChanged();

        replayAll();

        conference.setContents(new ArrayList<>(Arrays.asList(contentMock)));
        conference.propertyChange(new PropertyChangeEvent(speechActivityMock,
            ConferenceSpeechActivity.ENDPOINTS_PROPERTY_NAME, null, null));

        verifyAll();
    }

    private void expectDominantSpeakerChanged(Capture<String> messageArgument)
        throws Exception
    {
        expect(speechActivityMock.getDominantEndpoint())
            .andReturn(endpointMock);

        expect(endpointMock.isExpired()).andReturn(false);
        endpointMock.sendMessageOnDataChannel(capture(messageArgument));

        conference.setEndpoints(new LinkedList<>(Arrays.asList(endpointMock)));
    }

    @SuppressWarnings("unchecked")
    private void expectSpeechActivityEndpointsChanged()
    {
        expect(speechActivityMock.getEndpoints()).andReturn(endpoints);
        expect(contentMock.getMediaType()).andReturn(MediaType.VIDEO);
        expect(contentMock.getChannels())
            .andReturn(new Channel[] { channelMock });
        expect(channelMock.speechActivityEndpointsChanged(endpoints))
            .andReturn(endpoints);
        contentMock.askForKeyframes((List<Endpoint>) anyObject());
    }
}
