package org.geotools.data.wfs.internal.parsers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;
import org.geotools.data.DataSourceException;
import org.geotools.data.complex.ComplexFeatureConstants;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.AttributeBuilder;
import org.geotools.feature.AttributeImpl;
import org.geotools.feature.LenientFeatureFactoryImpl;
import org.geotools.feature.NameImpl;
import org.geotools.feature.ComplexFeatureBuilder;
import org.geotools.feature.Types;
import org.geotools.feature.type.AttributeDescriptorImpl;
import org.geotools.gml3.GML;
import org.opengis.feature.Attribute;
import org.opengis.feature.ComplexAttribute;
import org.opengis.feature.Feature;
import org.opengis.filter.FilterFactory;
import org.opengis.feature.Property;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.AttributeType;
import org.opengis.feature.type.ComplexType;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryType;
import org.opengis.feature.type.Name;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.feature.type.PropertyType;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class XmlComplexFeatureParser extends
		XmlFeatureParser<FeatureType, Feature> {

	private final ComplexFeatureBuilder featureBuilder;

	public XmlComplexFeatureParser(InputStream getFeatureResponseStream,
			FeatureType targetType, QName featureDescriptorName)
			throws IOException {
		super(getFeatureResponseStream, targetType, featureDescriptorName);
		this.featureBuilder = new ComplexFeatureBuilder(this.targetType);
	}

	int tabs = 0;

	@Override
	public Feature parse() throws IOException {
		final String fid;

		try {
			// Get the feature id or return null if there isn't one:
			if ((fid = seekFeature()) == null) {
				return null;
			}

			ReturnAttribute nextAttribute;
			while ((nextAttribute = parseNextAttribute(this.targetType)) != null) {
				if (!Property.class.isAssignableFrom(nextAttribute.value.getClass())) {
					featureBuilder.append(
						nextAttribute.name,
						new AttributeImpl(
							nextAttribute.value, 
							(AttributeDescriptor)this.targetType.getDescriptor(nextAttribute.name), 
							null));
				}
				else {
					featureBuilder.append(nextAttribute.name, (Property)nextAttribute.value);
				}
			}
		} catch (XmlPullParserException e) {
			throw new DataSourceException(e);
		}

		return featureBuilder.buildFeature(fid);
	}

	private Map<String, Attribute> discoveredComplexAttributes = new HashMap<String, Attribute>();

	private Map<String, ArrayList<Attribute>> placeholderComplexAttributes = new HashMap<String, ArrayList<Attribute>>();

	private void RegisterGmlTarget(String id, Attribute value) {
		// Add the value to the discoveredComplexAttributes object:
		discoveredComplexAttributes.put(id, value);

		// Check whether anything is waiting for this attribute and, if so,
		// populate them.
		if (placeholderComplexAttributes.containsKey(id)) {
			for (Attribute placeholderComplexAttribute : this.placeholderComplexAttributes.get(id)) {
				placeholderComplexAttribute.setValue(value.getValue());
			}
		}
	}

	private Attribute ResolveHref(String href, AttributeType expectedType) {
		// See what kind of href it is:
		if (href.startsWith("#")) {
			String hrefId = href.substring(1);

			// Does the target of this href already exist in the
			// discoveredComplexAttributes object?
			if (discoveredComplexAttributes.containsKey(hrefId)) {
				// If it does, we can just return that.
				return discoveredComplexAttributes.get(hrefId);
			} else {
				// If not, then we create a placeholderComplexAttribute instead:
				Attribute placeholderComplexAttribute = new AttributeImpl(
						Collections.<Property> emptyList(), expectedType, null);
				
				// I must maintain a reference back to this object so that I can
				// change it once its target is found:
				if (!placeholderComplexAttributes.containsKey(hrefId)) {
					placeholderComplexAttributes.put(hrefId,
							new ArrayList<Attribute>());
				}

				// Adding it to a list allows us to have multiple hrefs pointing
				// to the same target.
				placeholderComplexAttributes.get(hrefId).add(
						placeholderComplexAttribute);
				return placeholderComplexAttribute;
			}
		} else {
			// TODO: Handle remote hrefs.

			// This is temporary code to get things to work:
			Attribute placeholderComplexAttribute = new AttributeImpl(
					Collections.<Property> emptyList(), expectedType, null);

			return placeholderComplexAttribute;
		}

		// return null;
	}
	
	/**
     * Get base (non-collection) type of simple content.
     * 
     * @param type
     * @return
     */
    static AttributeType getSimpleContentType(AttributeType type) {
        Class<?> binding = type.getBinding();
        if (binding == Collection.class) {
            return getSimpleContentType(type.getSuper());
        } else {
            return type;
        }
    }

	/**
	 * This is a recursive method that returns any object that belongs to the
	 * complexType specified. The return object is wrapped in a ReturnAttribute
	 * which carries through some values related to the object. They are: its
	 * GML Id and its name.
	 * 
	 * @param complexType
	 *            The complex type that the CALLER is trying to build. NB: this
	 *            is NOT the type that the method will build, it's the type that
	 *            the caller wants.
	 * @return A ReturnAttribute object which groups a (Name) name, (String) id,
	 *         and (Object) value that represent an attribute that belongs in
	 *         the complexType specified. Returns null once there are no more
	 *         elements in the complex type you're trying to parse.
	 * @throws XmlPullParserException
	 * @throws IOException
	 */
	private ReturnAttribute parseNextAttribute(ComplexType complexType)
			throws XmlPullParserException, IOException {
	
		// 1. Read through the XML until you come across a start tag, end tag or
		// the end of the document:
		int tagType;
		do {
			tagType = parser.next();
		} while (tagType != XmlPullParser.START_TAG
				&& tagType != XmlPullParser.END_TAG
				&& tagType != XmlPullParser.END_DOCUMENT);

		// 2. We'll take an action depending on the type of tag we got.
		if (tagType == XmlPullParser.START_TAG) {
			// 2a. A start tag has been found; if it belongs to the complexType
			// then we should parse it and return it.

			// 3. Convert the tag's name into a NameImpl and then see if
			// there's a descriptor by that name in the type:
			Name currentTagName = new NameImpl(parser.getNamespace(), parser.getName());

			PropertyDescriptor descriptor = complexType
					.getDescriptor(currentTagName);
			if (descriptor != null) {
				// 3a. We've found a descriptor for the tag's name in the
				// complexType.

				// Get the type that the descriptor relates to, and get the GML
				// Id if it's set:
				PropertyType type = descriptor.getType();

				String id = parser.getAttributeValue(GML.id.getNamespaceURI(),
						GML.id.getLocalPart());

				// Is it defined by an xlink?
				String href = parser.getAttributeValue("http://www.w3.org/1999/xlink", "href");

				// 4. Parse the tag's contents based on whether it's a
				if (href != null) {
					// Resolve the href:
					Attribute hrefAttribute = ResolveHref(href, (AttributeType) type);

					// We've got the attribute but the parser is still
					// pointing at this tag so
					// we have to advance it till we get to the end tag.
					while (parser.next() != XmlPullParser.END_TAG)
						;

					return new ReturnAttribute(id, currentTagName, hrefAttribute);
				}
				// ComplexType or an AttributeType.
				else if (type instanceof ComplexType) {
					// 4a. The element is a complex type so we must loop through
					// each of its internal elements and construct a complex
					// attribute.

					// The attribute that we get from parsing the next attribute.
					ReturnAttribute innerAttribute;

					// Configure the attribute builder to help build the complex attribute.
					AttributeBuilder attributeBuilder = new AttributeBuilder(
							new LenientFeatureFactoryImpl());
					attributeBuilder.setType((AttributeType) type);

						if (type.getBinding() == Collection.class &&
							Types.isSimpleContentType(type)) {
							
							// Get the value
							// 覧覧覧覧覧覧�

							// I'm calling 'next()' to move the cursor off the tag and into its body, otherwise getText() pulls the wrong part.
							parser.next();
							Object value = parser.getText();
							
							// create an empty list
							ArrayList<Property> list = new ArrayList<Property>();

							// Add the value to the list if it's not null or if nulls are allowed by the descriptor.
							if (value != null || descriptor.isNillable()) {// add the result of buildSimpleContent(type, value) to the list and return it.
								AttributeType simpleContentType = getSimpleContentType((AttributeType)type);
								
								FilterFactory ff = CommonFactoryFinder.getFilterFactory(null);
								Object convertedValue = ff.literal(value).evaluate(value, simpleContentType.getBinding());
								
						        AttributeDescriptor simpleContentDescriptor = new AttributeDescriptorImpl(simpleContentType,
						            ComplexFeatureConstants.SIMPLE_CONTENT, 1, 1, true, (Object) null);
						        list.add(new AttributeImpl(convertedValue, simpleContentDescriptor, null));
			                }
							
							// We've got the attribute but the parser is still
							// pointing at this tag so
							// we have to advance it till we get to the end tag.
							while (parser.next() != XmlPullParser.END_TAG)
								;
			                
							return new ReturnAttribute(id, currentTagName, list);
						}
					

					// 5. Loop over and parse all the attributes in this complex feature.
					while ((innerAttribute = parseNextAttribute((ComplexType) type)) != null) {
						// 6. Check the type of the parsed attribute.
						if (ComplexAttribute.class
								.isAssignableFrom(innerAttribute.value.getClass())) {
							// 6a. If it's a Property then we must add it to
							// a list before sending it to the builder.
							ArrayList<Property> properties = new ArrayList<Property>();
							properties.add((Property) innerAttribute.value);
							attributeBuilder.add(innerAttribute.id, properties,
									innerAttribute.name);
						} else {
							// 6b. If the parsed attribute is actually just
							// an object then it must belong to a simple
							// type in which case we can just add it to the
							// builder as is.

							attributeBuilder.add(innerAttribute.id,
									innerAttribute.value, innerAttribute.name);
						}
					}

					Attribute attribteValue;
					if (type instanceof FeatureType) {
						attribteValue = attributeBuilder.build(id);
					} else {
						attribteValue = attributeBuilder.build();
					}

					// If this item has an id we'll register it in case
					// anything else points to it with an xlink:
					if (id != null) {
						this.RegisterGmlTarget(id,
								(ComplexAttribute) attribteValue);
					}

					return new ReturnAttribute(id, currentTagName, attribteValue);
				} else if (type instanceof AttributeType
						|| type instanceof GeometryType) {
					// 4b. It's a simple type so we can use super's parseAttributeValue method.
					Object attributeValue = super.parseAttributeValue((AttributeDescriptor) descriptor);
					return new ReturnAttribute(id, currentTagName, attributeValue);
				}
			} else {
				// 3b. If the tag name doesn't belong to this type then something is wrong.
				throw new RuntimeException(
						String.format(
								"WFS response structure unexpected. Could not find descriptor in type '%s' for '%s'.",
								complexType, currentTagName));
			}
		} else if (tagType == XmlPullParser.END_DOCUMENT) {
			// 2b. Close the parser if we're at the end of the document.
			close();
		}

		// We don't need any special action if the tagType was END_TAG.
		return null;
	}

	private class ReturnAttribute {
		public final String id;

		public final Name name;

		public final Object value;

		public ReturnAttribute(String id, Name name, Object value) {
			this.id = id;
			this.name = name;
			this.value = value;
		}
	}
}
