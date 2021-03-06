/*******************************************************************************
 * Copyright (c) 2008, 2011 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.plugins.p2;

import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "update-site-p2-metadata")
public class UpdateSiteP2MetadataMojo extends AbstractP2MetadataMojo {
    @Override
    protected String getPublisherApplication() {
        return "org.eclipse.equinox.p2.publisher.UpdateSitePublisher";
    }
}
