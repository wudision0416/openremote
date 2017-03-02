/*
 * Copyright 2016, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.shared.asset;

import jsinterop.annotations.JsType;
import org.openremote.manager.shared.http.RequestParams;
import org.openremote.manager.shared.http.SuccessStatusCode;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetInfo;
import org.openremote.model.asset.HomeAssets;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Asset access rules:
 * <ul>
 *     <li>The superuser (the admin in the master realm) can access all assets.</li>
 *     <li>A regular user can have roles that allow read, write, or no access to assets with her authenticated realm.</li>
 *     <li>A regular user can have restricted access to a subset of "home" assets within her authenticated realm (see {@link HomeAssets})</li>
 * </ul>
 *
 */
@Path("asset")
@JsType(isNative = true)
public interface AssetResource {

    /**
     * Retrieve the (restricted) home assets of the currently authenticated user. If the request is made
     * by the superuser, or if the regular user making the request is not restricted to home assets, the
     * root assets of the authenticated realm will be returned.
     */
    @GET
    @Path("home/current")
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(200)
    @RolesAllowed({"read:assets"})
    AssetInfo[] getHomeAssets(@BeanParam RequestParams requestParams);

    /**
     * Retrieve the assets without parent (root assets) of the given realm, or if the realm is empty,
     * of the authenticated realm. Regular users can only access assets in their authenticated realm,
     * the superuser can access assets in other (all) realms. A 403 status is returned if a regular
     * user tries to access a realm different than her authenticated realm.
     */
    @GET
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(200)
    @RolesAllowed({"read:assets"})
    AssetInfo[] getRoot(@BeanParam RequestParams requestParams, @QueryParam("realm") String realm);

    /**
     * Retrieve the child assets of the given parent asset. If the authenticated user is the superuser,
     * parent and child assets can be in any realm. Otherwise, assets must in the same realm as the
     * authenticated user. An empty result is returned if the user does not have access to the assets.
     */
    @GET
    @Path("{assetId}/children")
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(200)
    @RolesAllowed({"read:assets"})
    AssetInfo[] getChildren(@BeanParam RequestParams requestParams, @PathParam("assetId") String parentId);

    /**
     * Retrieve the asset. Regular users can only access assets in their authenticated realm,
     * the superuser can access assets in other (all) realms. A 403 status is returned if a regular
     * user tries to access an asset in a realm different than her authenticated realm.
     */
    @GET
    @Path("{assetId}")
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(200)
    @RolesAllowed({"read:assets"})
    Asset get(@BeanParam RequestParams requestParams, @PathParam("assetId") String assetId);

    /**
     * Updates the asset. Regular users can only update assets in their authenticated realm,
     * the superuser can update assets in other (all) realms. A 403 status is returned if a regular
     * user tries to update an asset in a realm different than her authenticated realm.
     */
    @PUT
    @Path("{assetId}")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(204)
    @RolesAllowed({"write:assets"})
    void update(@BeanParam RequestParams requestParams, @PathParam("assetId") String assetId, Asset asset);

    /**
     * Creates an asset. The identifier value of the asset can be provided, it should be a
     * globally unique string value, and must be at least 22 characters long. If no identifier
     * value is provided, a unique value will be generated by the system upon insert. Regular
     * users can only create assets in their authenticated realm, the superuser can create
     * assets in other (all) realms. A 403 status is returned if a regular user tries to create
     * an asset in a realm different than her authenticated realm.
     */
    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(204)
    @RolesAllowed({"write:assets"})
    void create(@BeanParam RequestParams requestParams, Asset asset);

    /**
     * Deletes an asset. Regular users can only delete assets in their authenticated realm,
     * the superuser can delete assets in other (all) realms. A 403 status is returned if a regular
     * user tries to delete an asset in a realm different than her authenticated realm.
     */
    @DELETE
    @Path("{assetId}")
    @Produces(APPLICATION_JSON)
    @SuccessStatusCode(204)
    @RolesAllowed({"write:assets"})
    void delete(@BeanParam RequestParams requestParams, @PathParam("assetId") String assetId);
}
