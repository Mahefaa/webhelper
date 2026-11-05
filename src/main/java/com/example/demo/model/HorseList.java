package com.example.demo.model;

import com.example.demo.PojaGenerated;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@PojaGenerated
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JacksonXmlRootElement(localName = "Horses")
public class HorseList {

  @JacksonXmlElementWrapper(useWrapping = false)
  @JacksonXmlProperty(localName = "Horse")
  private List<Horse> horses;
}
